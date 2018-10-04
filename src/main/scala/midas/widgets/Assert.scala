package midas
package widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}

import scala.collection.immutable.ListMap

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}

import junctions._
import midas.{NumAsserts, PrintPorts}
import midas.core.{Endpoint, HostPort}


class AssertBundle(val numAsserts: Int) extends Bundle {
  val asserts = Output(UInt(numAsserts.W))
}

object AssertBundle {
  def apply(port: firrtl.ir.Port): AssertBundle = {
    new AssertBundle(firrtl.bitWidth(port.tpe).toInt)
  }
}

class AssertWidgetIO(val numAsserts: Int)(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new AssertBundle(numAsserts)))
  val dma = None
}

class AssertBundleEndpoint extends Endpoint {
  var numAsserts = 0
  var initialized = false
  def matchType(data: Data) = data match {
    case channel: AssertBundle =>
      require(DataMirror.directionOf(channel) == Direction.Output, "AssertBundle has unexpected direction")
      // Can't do this as matchType is invoked multiple times
      //require(!initialized, "Can only match on one instance of AssertBundle")
      println(s"WHAT: ${data.instanceName}")
      initialized = true
      numAsserts = channel.numAsserts
      true
    case _ => false
  }
  def widget(p: Parameters) = {
    require(initialized, "Attempted to generate an AssertWidget before inspecting input data bundle")
    new AssertWidget(numAsserts)(p)
  }
  override def widgetName = "AssertionWidget"
}

class AssertWidget(numAsserts: Int)(implicit p: Parameters) extends EndpointWidget()(p) with HasChannels {
  val io = IO(new AssertWidgetIO(numAsserts))
  val resume = Wire(init=false.B)
  val cycles = Reg(UInt(64.W))
  val tResetAsserted = RegInit(false.B)
  val asserts = io.hPort.hBits.asserts
  val assertId = asserts >> 1
  val assertFire = asserts(0) && tResetAsserted && !io.tReset.bits
  val stallN = (!assertFire || resume)
  val dummyPredicate = true.B

  val tFireHelper = DecoupledHelper(io.hPort.toHost.hValid, io.tReset.valid, stallN, dummyPredicate)
  val targetFire = tFireHelper.fire(dummyPredicate) // FIXME: On next RC bump
  io.tReset.ready := tFireHelper.fire(io.tReset.valid)
  io.hPort.toHost.hReady := tFireHelper.fire(io.hPort.toHost.hValid)
  // We only sink tokens, so tie off the return channel
  io.hPort.fromHost.hValid := true.B
  when (targetFire) {
    cycles := cycles + 1.U
    when (io.tReset.bits) {
      tResetAsserted := true.B
    }
  }

  genROReg(assertId, "id")
  genROReg(assertFire, "fire")
  // FIXME: no hardcode
  genROReg(cycles(31, 0), "cycle_low")
  genROReg(cycles >> 32, "cycle_high")
  Pulsify(genWORegInit(resume, "resume", false.B), pulseLength = 1)
  genCRFile()
}


class PrintRecord(es: Seq[(String, Int)]) extends Record {
  val elements = ListMap((es map {
    case (name, width) => name -> Output(UInt(width.W))
  }):_*)
  def cloneType = new PrintRecord(es).asInstanceOf[this.type]
}

object PrintRecord {
  def apply(port: firrtl.ir.Port): PrintRecord = {
    val fields = port.tpe match {
      case firrtl.ir.BundleType(fs) => fs map (f => f.name -> firrtl.bitWidth(f.tpe).toInt)
    }
    new PrintRecord(fields)
  }
}

//class PrintRecordEndpoint extends Endpoint {
//  def matchType(data: Data) = data match {
//    case channel: PrintRecord =>
//      require(DataMirror.directionOf(channel) == Direction.Output, "PrintRecord has unexpected direction")
//      true
//    case _ => false
//  }
//  def widget(p: Parameters) = {
//    new PrintWidget()(p)
//  }
//  override def widgetName = "PrintWidget"
//}
//
//class PrintWidgetIO(implicit p: Parameters) extends EndpointWidgetIO()(p) {
//  val prints = Flipped(Decoupled(new PrintRecord(p(PrintPorts))))
//  val dma = Flipped(new WidgetMMIO()(p alterPartial ({ case NastiKey => p(midas.core.DMANastiKey) })))
//}
//
//class PrintWidget(implicit p: Parameters) extends EndpointWidget()(p) with HasChannels {
//  require(p(PrintPorts).size <= 8)
//
//  val io = IO(new PrintWidgetIO)
//  val fire = Wire(Bool())
//  val cycles = Reg(UInt(48.W))
//  val enable = RegInit(false.B)
//  val enableAddr = attach(enable, "enable")
//  val printAddrs = collection.mutable.ArrayBuffer[Int]()
//  val countAddrs = collection.mutable.ArrayBuffer[Int]()
//  val channelWidth = if (p(HasDMAChannel)) io.dma.nastiXDataBits else io.ctrl.nastiXDataBits
//  val printWidth = (io.prints.bits.elements foldLeft 56)(_ + _._2.getWidth - 1)
//  val valid = (io.prints.bits.elements foldLeft false.B)( _ || _._2(0))
//  val ps = io.prints.bits.elements.toSeq map (_._2 >> 1)
//  val vs = io.prints.bits.elements.toSeq map (_._2(0))
//  val data = Cat(Cat(ps.reverse), Cat(vs.reverse) | 0.U(8.W), cycles)
//  /* FIXME */ if (p(HasDMAChannel)) assert(printWidth <= channelWidth)
//
//  val prints = (0 until printWidth by channelWidth).zipWithIndex map { case (off, i) =>
//    val width = channelWidth min (printWidth - off)
//    val wires = Wire(Decoupled(UInt(width.W)))
//    // val queue = Queue(wires, 8 * 1024)
//    val queue = BRAMQueue(wires, 8 * 1024)
//    wires.bits  := data(width + off - 1, off)
//    wires.valid := fire && enable && valid
//    if (countAddrs.isEmpty) {
//      val count = RegInit(0.U(24.W))
//      count suggestName "count"
//      when (wires.fire() === queue.fire()) {
//      }.elsewhen(wires.fire()) {
//        count := count + 1.U
//      }.elsewhen(queue.fire()) {
//        count := count - 1.U
//      }
//      countAddrs += attach(count, "prints_count", ReadOnly)
//    }
//
//    if (p(HasDMAChannel)) {
//      val arQueue   = Queue(io.dma.ar, 10)
//      val readBeats = RegInit(0.U(9.W))
//      readBeats suggestName "readBeats"
//      when(io.dma.r.fire()) {
//        readBeats := Mux(io.dma.r.bits.last, 0.U, readBeats + 1.U)
//      }
//
//      queue.ready := io.dma.r.ready && arQueue.valid
//      io.dma.r.valid := queue.valid && arQueue.valid
//      io.dma.r.bits.data := queue.bits
//      io.dma.r.bits.last := arQueue.bits.len === readBeats
//      io.dma.r.bits.id   := arQueue.bits.id
//      io.dma.r.bits.user := arQueue.bits.user
//      io.dma.r.bits.resp := 0.U
//      arQueue.ready := io.dma.r.fire() && io.dma.r.bits.last
//
//      // No write
//      io.dma.aw.ready := false.B
//      io.dma.w.ready := false.B
//      io.dma.b.valid := false.B
//    } else {
//      printAddrs += attachDecoupledSource(queue, s"prints_data_$i")
//    }
//    wires.ready || !valid
//  }
//  fire := (prints foldLeft (io.prints.valid && io.tReset.valid))(_ && _)
//  io.tReset.ready := fire
//  io.prints.ready := fire
//  when (fire) {
//    cycles := Mux(io.tReset.bits, 0.U, cycles + 1.U)
//  }
//
//  override def genHeader(base: BigInt, sb: StringBuilder) {
//    import CppGenerationUtils._
//    sb.append(genComment("Print Widget"))
//    sb.append(genMacro("PRINTS_NUM", UInt32(p(PrintPorts).size)))
//    sb.append(genMacro("PRINTS_CHUNKS", UInt32(prints.size)))
//    sb.append(genMacro("PRINTS_ENABLE", UInt32(base + enableAddr)))
//    sb.append(genMacro("PRINTS_COUNT_ADDR", UInt32(base + countAddrs.head)))
//    sb.append(genArray("PRINTS_DATA_ADDRS", printAddrs.toSeq map (x => UInt32(base + x))))
//    sb.append(genArray("PRINTS_WIDTHS", io.prints.bits.elements.toSeq map (x => UInt32(x._2.getWidth))))
//    if (p(HasDMAChannel)) sb.append(genMacro("HAS_DMA_CHANNEL"))
//  }
//
//  genCRFile()
//}
