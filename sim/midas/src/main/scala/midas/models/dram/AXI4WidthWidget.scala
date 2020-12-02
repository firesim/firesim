// See LICENSE for license details.
package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.amba.axi4._

class XactionMetadata(val ratio: Int, val masterBytes: Int) extends Bundle {
  val len = UInt(AXI4Parameters.lenBits.W)
  val size = UInt(AXI4Parameters.sizeBits.W)
  // This to handle narrow writes that are still wider than the slave
  val beatRatio = UInt(log2Ceil(ratio + 1).W)
  val addressOffset = UInt(log2Ceil(masterBytes).W)
}

class AXI4WidthWidget(masterBytes: Int, capMaxFlight: Option[Int] = None)(implicit p: Parameters) extends LazyModule {
  require(isPow2(masterBytes))
  val node = AXI4AdapterNode(
    masterFn  = { case mp => mp.copy(
      masters = mp.masters.map { m => m.copy(
        maxFlight = (m.maxFlight, capMaxFlight) match {
          case (Some(x), Some(y)) => Some(x min y)
          case (Some(x), None)    => Some(x)
          case (None,    Some(y)) => Some(y)
          case (None,    None)    => None })})
      },
    slaveFn   = { case s =>
      require(s.beatBytes <= masterBytes, "TODO: Handle upsizing later")
      s.copy(beatBytes = masterBytes)})

  lazy val module = new LazyModuleImp(this) {
    val (io_in, edgesIn) = node.in.unzip
    val (io_out, edgesOut) = node.out.unzip
    for (((io_in, edgeIn) , (io_out, edgeOut)) <- node.in.zip(node.out)) {
      val slaveBytes = edgeOut.slave.beatBytes
      val slaveMaxSize = log2Ceil(slaveBytes)
      val masterMaxSize = log2Ceil(masterBytes)
      val ratio = masterBytes / slaveBytes

      edgeOut.master.masters.foreach { m =>
        require (m.maxFlight.isDefined,
          "WidthAdapter needs a flight cap on each ID. Consider using a UserYanker.")
      }

      edgeIn.slave.slaves.foreach { s =>
        require (s.interleavedId == None, 
          "WidthAdapter cannot handle interleaved read response beats. Use a Deinterleaver.")
      }

      if (slaveBytes == masterBytes) {
        io_out <> io_in
      } else {
        assert(!io_in.ar.valid || io_in.ar.bits.burst === AXI4Parameters.BURST_INCR)
        assert(!io_in.aw.valid || io_in.aw.bits.burst === AXI4Parameters.BURST_INCR)

        io_out.aw :<> io_in.aw
        when(io_in.aw.bits.size > slaveMaxSize.U) {
          io_out.aw.bits.len  := (io_in.aw.bits.len * ratio.U) + (ratio - 1).U
          io_out.aw.bits.size :=  slaveMaxSize.U
        }

        io_in.b :<> io_out.b

        val awQueue = Module(new Queue(new XactionMetadata(ratio, masterBytes), entries = 2, flow = true))
        awQueue.io.enq.valid := io_out.aw.ready && io_in.aw.valid
        io_out.aw.valid := awQueue.io.enq.ready && io_in.aw.valid
        io_in.aw.ready := awQueue.io.enq.ready && io_out.aw.ready

        awQueue.io.enq.bits.len := io_out.aw.bits.len
        awQueue.io.enq.bits.size := io_out.aw.bits.size
        awQueue.io.enq.bits.beatRatio := 1.U << (io_out.aw.bits.size - io_in.aw.bits.size)
        awQueue.io.enq.bits.addressOffset := io_out.aw.bits.addr(masterMaxSize - 1, 0)

        val wValid = RegInit(false.B)
        val wXaction = Reg(awQueue.io.deq.bits.cloneType)

        val canProcessNextWrite = !wValid || io_out.w.ready && io_out.w.bits.last
        awQueue.io.deq.ready := canProcessNextWrite

        when(canProcessNextWrite) {
          wValid := awQueue.io.deq.valid
          wXaction :=  awQueue.io.deq.bits
        }.elsewhen(io_out.w.fire) {
          wXaction.len := wXaction.len - 1.U
          wXaction.addressOffset := wXaction.addressOffset + (1.U << wXaction.size)
        }

        val inputBeatDone = (wXaction.len & (wXaction.beatRatio - 1.U)) === 0.U
        val wSliceIdx = wXaction.addressOffset >> log2Ceil(slaveBytes)

        def helper(idata: UInt, index: UInt, width: Int): UInt = {
          val mux = VecInit.tabulate(ratio) { i => idata((i+1)*slaveBytes*width-1, i*slaveBytes*width) }
          mux(index)
        }

        io_out.w.bits := io_in.w.bits
        io_out.w.bits.data := helper(io_in.w.bits.data, wSliceIdx, 8)
        io_out.w.bits.strb := helper(io_in.w.bits.strb, wSliceIdx, 1)
        io_out.w.bits.last := wXaction.len === 0.U
        io_out.w.valid := wValid && io_in.w.valid
        io_in.w.ready := wValid && io_out.w.ready && inputBeatDone

        val wAssertMask = io_out.w.valid && io_in.w.valid
        assert(!(wAssertMask && io_out.w.bits.last) || io_in.w.bits.last,
          "Output wlast asserted when input wlast was not")

        io_out.ar :<> io_in.ar
        when(io_in.ar.bits.size > slaveMaxSize.U) {
          io_out.ar.bits.len := (io_in.ar.bits.len << ratio.U) + (ratio - 1).U
          io_out.ar.bits.size :=  slaveMaxSize.U
        }

        val aridSel = UIntToOH(io_in.ar.bits.id).asBools
        def queue(id: Int): (QueueIO[XactionMetadata], Bool) = {
          val depth = edgeOut.master.masters.find(_.id.contains(id)).flatMap(_.maxFlight).getOrElse(0)
          if (depth == 0) {
            assert(!io_in.ar.valid || !aridSel(id), "Received transaction on Id ${id} for which maxFlight = 0")
            (Wire(new QueueIO(new XactionMetadata(ratio, masterBytes), 1)), false.B) // unused ID => undefined value
          } else {
            val q = Module(new Queue(new XactionMetadata(ratio, masterBytes), depth)).io
            q.enq.bits.len := io_out.ar.bits.len
            q.enq.bits.size := io_out.ar.bits.size
            q.enq.bits.beatRatio := 1.U << (io_out.ar.bits.size - io_in.ar.bits.size)
            q.enq.bits.addressOffset := io_out.ar.bits.addr(masterMaxSize - 1, 0)
            q.enq.valid := aridSel(id) && io_in.ar.valid && io_out.ar.ready
            (q, aridSel(id) && q.enq.ready)
          }
        }

        val (arQueues, readies) = Seq.tabulate(edgeIn.master.endId)(i => queue(i)).unzip
        val queuesReady = readies.reduce { _ || _ }
        io_in.ar.ready := queuesReady && io_out.ar.ready
        io_out.ar.valid := queuesReady && io_in.ar.valid

        val rXactionValid = RegInit(false.B)
        val rXactionReg = Reg(arQueues.head.deq.bits.cloneType)
        val rXaction = Mux(rXactionValid, rXactionReg, VecInit(arQueues.map(_.deq.bits))(io_out.r.bits.id))

        val rValid = RegInit(false.B)
        val rData = Reg(Vec(ratio, UInt((slaveBytes * 8).W)))
        val rResp = RegInit(AXI4Parameters.RESP_OKAY)

        when(io_in.r.fire && rXaction.len === 0.U) {
          rXactionValid := false.B
        }.elsewhen(io_out.r.fire) {
          rXactionValid := true.B
          rXactionReg := rXaction
          rXactionReg.len := rXaction.len - 1.U
          rXactionReg.addressOffset := rXaction.addressOffset + (1.U << rXaction.size)
        }

        val sliceIdx = rXaction.addressOffset(masterMaxSize - 1, slaveMaxSize)
        when(io_out.r.fire) {
          rData(sliceIdx) := io_out.r.bits.data
        }
        val sliceSel = UIntToOH(sliceIdx).asBools
        val rDataOut = VecInit(rData.zip(sliceSel).map { case (data, en) =>
          Mux(en, io_out.r.bits.data, data)
        }).asUInt

        // Make RRESP sticky in case of any failure across the beats
        when(io_in.r.fire) {
          rResp := Mux(io_out.r.valid, io_out.r.bits.resp, AXI4Parameters.RESP_OKAY)
        }.elsewhen(io_out.r.fire) {
          rResp := io_in.r.bits.resp
        }

        io_in.r.bits := io_out.r.bits
        io_in.r.bits.data := rDataOut
        io_in.r.bits.resp := Mux(rResp =/= AXI4Parameters.RESP_OKAY, rResp, io_out.r.bits.resp)

        val readBeatDone = (rXaction.len & (rXaction.beatRatio - 1.U)) === 0.U
        io_in.r.valid := readBeatDone && io_out.r.valid
        io_out.r.ready := rXaction.len =/= 0.U || io_in.r.ready
        val ridSel = UIntToOH(io_out.r.bits.id).asBools
        for ((q, en) <- arQueues.zip(ridSel)) {
          q.deq.ready := en && io_in.r.fire
        }
      }
    }
  }
}
object AXI4WidthWidget {
  def apply(innerBeatBytes: Int)(implicit p: Parameters): AXI4Node = {
    val widget = LazyModule(new AXI4WidthWidget(innerBeatBytes))
    widget.node
  }
}

/** Synthesizeable unit tests */
import freechips.rocketchip.unittest._

class AXI4WidthWidgetDUT(first: Int, txns: Int)(implicit p: Parameters) extends LazyModule {
  val master = LazyModule(new AXI4FuzzMaster(txns))
  val slave  = LazyModule(new AXI4FuzzSlave)
  (slave.node := AXI4WidthWidget(first)
    := master.node)

  lazy val module = new LazyModuleImp(this) with UnitTestModule {
    io.finished := master.module.io.finished
  }
}

class AXI4WidthWidgetTest(txns: Int = 5000, timeout: Int = 500000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new AXI4WidthWidgetDUT(8, txns)).module)
  dut.io.start := DontCare
  io.finished := dut.io.finished
}
