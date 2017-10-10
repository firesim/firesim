// See LICENSE for license details.

package midas
package widgets

import chisel3._
import chisel3.util._
import junctions._
import config.{Parameters, Field}

case class MidasLLCParameters(nWays: Int, nSets: Int, blockBytes: Int)
case object MidasLLCKey extends Field[Option[MidasLLCParameters]]

class MidasLLCConfigBundle(key: MidasLLCParameters) extends Bundle {
  val wayBits = UInt(log2Ceil(key.nWays).W)
  val setBits = UInt(log2Ceil(key.nSets).W)
  val blockBits = UInt(log2Ceil(key.blockBytes).W)
  override def cloneType = new MidasLLCConfigBundle(key).asInstanceOf[this.type]
}

class MidasLLC(key: MidasLLCParameters)(implicit p: Parameters) extends NastiModule {
  val io = IO(new Bundle {
    val config = Input(new MidasLLCConfigBundle(key))
    val raddr = Flipped(Decoupled(UInt(nastiXAddrBits.W)))
    val waddr = Flipped(Decoupled(UInt(nastiXAddrBits.W)))
    val wlast = Flipped(Decoupled(Bool()))
    val resp = Valid(new Bundle {
      val hit = Bool()
      val wr = Bool()
    })
    val idle = Output(Bool())
  })
  println("[Midas Last Level Cache] # Ways <= %d, # Sets <= %d, Block Size <= %d B => Cache Size <= %d KiB".format(
          key.nWays, key.nSets, key.blockBytes, (key.nWays * key.nSets * key.blockBytes) / 1024))

  val sIdle :: sRead :: sRefill :: sReady :: Nil = Enum(UInt(), 4)
  val state = RegInit(sIdle)

  val raddrQueue = Queue(io.raddr)
  val waddrQueue = Queue(io.waddr)
  val wlastQueue = Queue(io.wlast)

  val wayBits = io.config.wayBits
  val setBits = io.config.setBits
  val blockBits = io.config.blockBits
  val tagBits = nastiXAddrBits.U - (setBits + blockBits)
  val wayMask = (1.U << wayBits) - 1.U
  val idxMask = (1.U << setBits) - 1.U
  val tagMask = (1.U << tagBits) - 1.U

  val is_wr = waddrQueue.valid && wlastQueue.valid
  val has_addr = is_wr || raddrQueue.valid
  val addr = Mux(is_wr, waddrQueue.bits, raddrQueue.bits)
  val idx = ((addr >> blockBits) & idxMask)(log2Ceil(key.nSets) - 1, 0)
  val tag = ((addr >> (setBits + blockBits)) & tagMask)

  val is_wr_reg = RegEnable(is_wr, state === sIdle)
  val idx_reg = RegEnable(idx, state === sIdle)
  val tag_reg = RegEnable(tag, state === sIdle)

  val ren = has_addr && state === sIdle
  val v = Seq.fill(key.nWays)(Mem(key.nSets, Bool()))
  val tags = Seq.fill(key.nWays)(SeqMem(key.nSets, UInt((nastiXAddrBits-8).W)))
  val tag_reads = tags map (_.read(idx, ren) & tagMask)
  val tag_matches = tag_reads map (_ === tag_reg)
  val match_way = Vec(tag_matches) indexWhere ((x: Bool) => x)

  io.resp.bits.hit := Vec(v map (_(idx)))(match_way) && (tag_matches reduce (_ || _))
  io.resp.bits.wr := is_wr_reg
  io.resp.valid := state === sRead
  io.idle := state === sIdle && !has_addr

  // TODO: LRU?
  val wen = !ren && state === sRead && !io.resp.bits.hit
  val valid_all = ((v map (_(idx_reg))).zipWithIndex foldLeft true.B){
    case (res, (x, way)) => res && (x || wayMask < way.U) }
  val invalid_way = Vec(v map (_(idx_reg))) indexWhere ((x: Bool) => !x)
  val lsfr = LFSR16(!io.resp.bits.hit && io.resp.valid) // is it right?
  val repl_way = Mux(wayBits === 0.U, 0.U,
                 Mux(valid_all, lsfr & wayMask, invalid_way))

  when(wen) {
    (0 until key.nWays) foreach { i =>
      when(i.U === repl_way) {
        v(i)(idx_reg) := true.B
        tags(i).write(idx_reg, tag_reg)
      }
    }
  }

  raddrQueue.ready := state === sReady && !is_wr_reg
  waddrQueue.ready := state === sReady && is_wr_reg
  wlastQueue.ready := state === sReady && is_wr_reg
  switch(state) {
    is(sIdle) {
      when(has_addr) {
        state := sRead
      }
    }
    is(sRead) {
      state := Mux(io.resp.bits.hit, sReady, sRefill)
    }
    is(sRefill) {
      state := sReady
    }
    is(sReady) {
      state := sIdle
    }
  }
}

class SimpleLatencyPipe(implicit val p: Parameters) extends NastiWidgetBase {
  // Timing Model
  val rCycles = Module(new Queue(UInt(64.W), 8))
  val wCycles = Module(new Queue(UInt(64.W), 8))
  val rCycleValid = Wire(Bool())
  val wCycleValid = Wire(Bool())
  val rCycleReady = Wire(Bool())
  val wCycleReady = Wire(Bool())
  val l2Idle = Wire(Bool())

  // Control Registers
  val memLatency = RegInit(32.U(32.W))
  val l2Latency = RegInit(8.U(32.W))
  // LLC Size: 256KiB by default
  val wayBits = RegInit(2.U(32.W)) // # Ways = 4
  val setBits = RegInit(10.U(32.W)) // # Sets = 1024
  val blockBits = RegInit(6.U(32.W)) // # blockSize = 64 Bytes

  val stall = (rCycleValid && !rBuf.io.deq.valid) ||
              (wCycleValid && !bBuf.io.deq.valid) || !l2Idle
  val (fire, cycles, targetReset) = elaborate(
    stall, rCycleValid, wCycleValid, rCycleReady, wCycleReady)

  val latency = p(MidasLLCKey) match {
    case None =>
      rCycleReady := rCycles.io.enq.ready
      wCycleReady := wCycles.io.enq.ready
      rCycles.io.enq.valid := tNasti.ar.fire() && fire
      wCycles.io.enq.valid := tNasti.w.fire() && tNasti.w.bits.last && fire
      l2Idle := true.B
      memLatency
    case Some(key: MidasLLCParameters) =>
      val l2 = Module(new MidasLLC(key))
      l2.io.config.wayBits := wayBits
      l2.io.config.setBits := setBits
      l2.io.config.blockBits := blockBits
      l2.io.raddr.bits  := tNasti.ar.bits.addr
      l2.io.raddr.valid := tNasti.ar.valid && fire
      l2.io.waddr.bits  := tNasti.aw.bits.addr
      l2.io.waddr.valid := tNasti.aw.valid && fire
      l2.io.wlast.bits  := Bool(true)
      l2.io.wlast.valid := tNasti.w.valid && tNasti.w.bits.last && fire
      l2Idle := l2.io.idle
      rCycleReady := rCycles.io.enq.ready && l2.io.raddr.ready
      wCycleReady := wCycles.io.enq.ready && l2.io.waddr.ready && l2.io.wlast.ready
      rCycles.io.enq.valid := l2.io.resp.valid && !l2.io.resp.bits.wr
      wCycles.io.enq.valid := l2.io.resp.valid && l2.io.resp.bits.wr
      Mux(l2.io.resp.bits.hit, l2Latency, memLatency)
  }

  rCycles.reset := reset || targetReset
  wCycles.reset := reset || targetReset
  rCycleValid := rCycles.io.deq.valid && rCycles.io.deq.bits <= cycles
  wCycleValid := wCycles.io.deq.valid && wCycles.io.deq.bits <= cycles
  rCycles.io.enq.bits  := cycles + latency
  wCycles.io.enq.bits  := cycles + latency
  rCycles.io.deq.ready := tNasti.r.fire() && tNasti.r.bits.last && fire
  wCycles.io.deq.ready := tNasti.b.fire() && fire

  io.host_mem.aw <> awBuf.io.deq
  io.host_mem.ar <> arBuf.io.deq
  io.host_mem.w  <> wBuf.io.deq
  rBuf.io.enq <> io.host_mem.r
  bBuf.io.enq <> io.host_mem.b

  // Connect all programmable registers to the control interrconect
  attach(memLatency, "MEM_LATENCY", WriteOnly)
  if (p(MidasLLCKey).isDefined) {
    attach(l2Latency, "LLC_LATENCY", WriteOnly)
    attach(wayBits, "LLC_WAY_BITS", WriteOnly)
    attach(setBits, "LLC_SET_BITS", WriteOnly)
    attach(blockBits, "LLC_BLOCK_BITS", WriteOnly)
  }
  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    crRegistry.genArrayHeader(getWName.toUpperCase, base, sb)
  }
}
