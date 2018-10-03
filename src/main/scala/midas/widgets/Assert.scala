package midas
package widgets

import midas.core.{NumAsserts, PrintPorts, PrintRecord}
import chisel3._
import chisel3.util._
import config.{Parameters, Field}
import junctions._

class AssertWidgetIO(implicit p: Parameters) extends WidgetIO()(p) {
  val tReset = Flipped(Decoupled(Bool()))
  val assert = Flipped(Decoupled(UInt((log2Ceil(p(NumAsserts) max 1) + 1).W)))
}

class AssertWidget(implicit p: Parameters) extends Widget()(p) with HasChannels {
  val io = IO(new AssertWidgetIO)
  val resume = Wire(init=false.B)
  val cycles = Reg(UInt(64.W))
  val assertId = io.assert.bits >> 1
  val assertFire = io.assert.bits(0) && !io.tReset.bits && cycles.orR
  val fire = io.assert.valid && io.tReset.valid && (!assertFire || resume)
  io.assert.ready := fire
  io.tReset.ready := fire
  when (fire) {
    cycles := Mux(io.tReset.bits, 0.U, cycles + 1.U)
  }

  genROReg(assertId, "id")
  genROReg(assertFire, "fire")
  // FIXME: no hardcode
  genROReg(cycles(31, 0), "cycle_low")
  genROReg(cycles >> 32, "cycle_high")
  Pulsify(genWORegInit(resume, "resume", false.B), pulseLength = 1)
  genCRFile()
}

class PrintWidgetIO(implicit p: Parameters) extends WidgetIO()(p) {
  val tReset = Flipped(Decoupled(Bool()))
  val prints = Flipped(Decoupled(new PrintRecord(p(PrintPorts))))
  val dma = Flipped(new WidgetMMIO()(p alterPartial ({ case NastiKey => p(midas.core.DMANastiKey) })))
}

class PrintWidget(implicit p: Parameters) extends Widget()(p) with HasChannels {
  require(p(PrintPorts).size <= 8)

  val io = IO(new PrintWidgetIO)
  val fire = Wire(Bool())
  val cycles = Reg(UInt(48.W))
  val enable = RegInit(false.B)
  val enableAddr = attach(enable, "enable")
  val printAddrs = collection.mutable.ArrayBuffer[Int]()
  val countAddrs = collection.mutable.ArrayBuffer[Int]()
  val channelWidth = if (p(HasDMAChannel)) io.dma.nastiXDataBits else io.ctrl.nastiXDataBits
  val printWidth = (io.prints.bits.elements foldLeft 56)(_ + _._2.getWidth - 1)
  val valid = (io.prints.bits.elements foldLeft false.B)( _ || _._2(0))
  val ps = io.prints.bits.elements.toSeq map (_._2 >> 1)
  val vs = io.prints.bits.elements.toSeq map (_._2(0))
  val data = Cat(Cat(ps.reverse), Cat(vs.reverse) | 0.U(8.W), cycles)
  /* FIXME */ if (p(HasDMAChannel)) assert(printWidth <= channelWidth)

  val prints = (0 until printWidth by channelWidth).zipWithIndex map { case (off, i) =>
    val width = channelWidth min (printWidth - off)
    val wires = Wire(Decoupled(UInt(width.W)))
    // val queue = Queue(wires, 8 * 1024)
    val queue = SeqQueue(wires, 8 * 1024)
    wires.bits  := data(width + off - 1, off)
    wires.valid := fire && enable && valid
    if (countAddrs.isEmpty) {
      val count = RegInit(0.U(24.W))
      count suggestName "count"
      when (wires.fire() === queue.fire()) {
      }.elsewhen(wires.fire()) {
        count := count + 1.U
      }.elsewhen(queue.fire()) {
        count := count - 1.U
      }
      countAddrs += attach(count, "prints_count", ReadOnly)
    }

    if (p(HasDMAChannel)) {
      val arQueue   = Queue(io.dma.ar, 10)
      val readBeats = RegInit(0.U(9.W))
      readBeats suggestName "readBeats"
      when(io.dma.r.fire()) {
        readBeats := Mux(io.dma.r.bits.last, 0.U, readBeats + 1.U)
      }

      queue.ready := io.dma.r.ready && arQueue.valid
      io.dma.r.valid := queue.valid && arQueue.valid
      io.dma.r.bits.data := queue.bits
      io.dma.r.bits.last := arQueue.bits.len === readBeats
      io.dma.r.bits.id   := arQueue.bits.id
      io.dma.r.bits.user := arQueue.bits.user
      io.dma.r.bits.resp := 0.U
      arQueue.ready := io.dma.r.fire() && io.dma.r.bits.last

      // No write
      io.dma.aw.ready := false.B
      io.dma.w.ready := false.B
      io.dma.b.valid := false.B
    } else {
      printAddrs += attachDecoupledSource(queue, s"prints_data_$i")
    }
    wires.ready || !valid
  }
  fire := (prints foldLeft (io.prints.valid && io.tReset.valid))(_ && _)
  io.tReset.ready := fire
  io.prints.ready := fire
  when (fire) {
    cycles := Mux(io.tReset.bits, 0.U, cycles + 1.U)
  }

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    sb.append(genComment("Print Widget"))
    sb.append(genMacro("PRINTS_NUM", UInt32(p(PrintPorts).size)))
    sb.append(genMacro("PRINTS_CHUNKS", UInt32(prints.size)))
    sb.append(genMacro("PRINTS_ENABLE", UInt32(base + enableAddr)))
    sb.append(genMacro("PRINTS_COUNT_ADDR", UInt32(base + countAddrs.head)))
    sb.append(genArray("PRINTS_DATA_ADDRS", printAddrs.toSeq map (x => UInt32(base + x))))
    sb.append(genArray("PRINTS_WIDTHS", io.prints.bits.elements.toSeq map (x => UInt32(x._2.getWidth))))
    if (p(HasDMAChannel)) sb.append(genMacro("HAS_DMA_CHANNEL"))
  }

  genCRFile()
}
