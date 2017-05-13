package midas
package widgets

import chisel3._
import chisel3.util._
import junctions._
import config.{Parameters, Field}

case class MidasL2Parameters(nWays: Int, nSets: Int, blockBytes: Int)
case object MidasL2Key extends Field[Option[MidasL2Parameters]]

class MidasL2Cache(key: MidasL2Parameters)(implicit p: Parameters) extends NastiModule {
  val io = IO(new Bundle {
    val ar = Flipped(Valid(new NastiReadAddressChannel))
    val aw = Flipped(Valid(new NastiWriteAddressChannel))
    val fire = Input(Bool())
    val hit = Valid(Bool())
    val ready = Output(Bool())
  })
  println("[Midas L2 Cache] # Ways: %d, # Sets: %d, Block Size: %d B => Cache Size: %d KiB".format(
          key.nWays, key.nSets, key.blockBytes, (key.nWays * key.nSets * key.blockBytes) / 1024))

  val sIdle :: sRead :: sRefill :: sReady :: Nil = Enum(UInt(), 4)
  val state = RegInit(sIdle)

  val wayBits = log2Ceil(key.nWays)
  val setBits = log2Ceil(key.nSets)
  val blockBits = log2Ceil(key.blockBytes)
  val tagBits = nastiXAddrBits - (setBits + blockBits)

  val addr = Mux(io.aw.valid, io.aw.bits.addr, io.ar.bits.addr)
  val idx = addr(setBits + blockBits - 1, blockBits)
  val idx_reg = RegEnable(idx, state === sIdle)
  val tag = RegEnable(addr >> (setBits + blockBits), state === sIdle)

  val ren = (io.aw.valid || io.ar.valid) && io.fire && state === sIdle
  val v = Seq.fill(key.nWays)(RegInit(0.U(key.nSets.W)))
  val tags = Seq.fill(key.nWays)(SeqMem(key.nSets, UInt(tagBits.W)))
  val tag_reads = tags map (_.read(idx, ren))
  val tag_matches = tag_reads map (_ === tag)
  val match_way = Vec(tag_matches) indexWhere ((x: Bool) => x)

  io.hit.bits := Vec(v)(match_way)(idx) && (tag_matches reduce (_ || _))
  io.hit.valid := state === sRead
  io.ready := state === sReady || (state === sIdle && !io.aw.valid && !io.ar.valid)

  // TODO: LRU?
  val wen = !ren && state === sRead && !io.hit.bits
  val valid_all = v map (_(idx_reg)) reduce (_ && _)
  val invalid_way = Vec(v map (_(idx_reg))) indexWhere ((x: Bool) => !x)
  val lsfr = LFSR16(io.fire && state === sIdle) // is it right?
  val repl_way = if (key.nWays == 1) 0.U else
    Mux(valid_all, lsfr(wayBits - 1, 0), invalid_way)

  when(wen) {
    (0 until key.nWays) foreach { i =>
      when(i.U === repl_way) {
        v(i) := v(i).bitSet(idx_reg, true.B)
        tags(i).write(idx_reg, tag)
      }
    }
  }

  switch(state) {
    is(sIdle) {
      when((io.aw.valid || io.ar.valid) && io.fire) {
        state := sRead
      }
    }
    is(sRead) {
      state := Mux(io.hit.bits, sReady, sRefill)
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
  val memLatency = RegInit(32.U(32.W))
  val l2Latency = RegInit(8.U(32.W))
  val l2Ready = Wire(Bool())

  val stall = (rCycleValid && !rBuf.io.deq.valid) || (wCycleValid && !bBuf.io.deq.valid) || !l2Ready
  val (fire, cycles, targetReset) = elaborate(
    stall, rCycleValid, wCycleValid, rCycles.io.enq.ready, wCycles.io.enq.ready)

  val latency = p(MidasL2Key) match {
    case None =>
      rCycles.io.enq.valid := tNasti.ar.fire() && fire
      wCycles.io.enq.valid := tNasti.w.fire() && tNasti.w.bits.last && fire
      l2Ready := true.B
      memLatency
    case Some(key: MidasL2Parameters) =>
      val l2 = Module(new MidasL2Cache(key))
      l2.io.ar.bits := tNasti.ar.bits
      l2.io.ar.valid := tNasti.ar.fire()
      l2.io.aw.bits := tNasti.aw.bits
      l2.io.aw.valid := tNasti.aw.fire()
      l2.io.fire := tFire
      rCycles.io.enq.valid := l2.io.ar.valid && l2.io.hit.valid
      wCycles.io.enq.valid := l2.io.aw.valid && l2.io.hit.valid
      l2Ready := l2.io.ready
      Mux(l2.io.hit.bits, l2Latency, memLatency)
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
  attach(memLatency, "MEM_LATENCY")
  attach(l2Latency,  "L2_LATENCY")
  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    crRegistry.genArrayHeader(getWName.toUpperCase, base, sb)
  }
}
