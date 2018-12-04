// See LICENSE for license details.

package midas
package widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}

import scala.collection.immutable.ListMap

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}

import junctions._
import midas.{PrintPorts}
import midas.core.{Endpoint, HostPort, DMANastiKey}



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

class PrintRecordEndpoint extends Endpoint {
  var initialized = false
  var printRecordGen: PrintRecord = new PrintRecord(Seq())

  def matchType(data: Data) = data match {
    case channel: PrintRecord =>
      require(DataMirror.directionOf(channel) == Direction.Output, "PrintRecord has unexpected direction")
      initialized = true
      printRecordGen = channel.cloneType
      true
    case _ => false
  }
  def widget(p: Parameters) = {
    require(initialized, "Attempted to generate an PrintWidget before inspecting input data bundle")
    new PrintWidget(printRecordGen)(p)
  }
  override def widgetName = "PrintWidget"
}

class PrintWidgetIO(private val record: PrintRecord)(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(record))
  override val dma = if (p(HasDMAChannel)) {
    Some(Flipped(new NastiIO()( p.alterPartial({ case NastiKey => p(DMANastiKey) }))))
  } else None
  override val dmaSize = if (p(HasDMAChannel)) {
    throw new RuntimeException("Damn it Howie.")
  } else BigInt(0)
}

class PrintWidget(printRecord: PrintRecord)(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new PrintWidgetIO(printRecord))
  val printPort = io.hPort.hBits
  val fire = Wire(Bool())
  val cycles = Reg(UInt(48.W))
  val enable = RegInit(false.B)
  val enableAddr = attach(enable, "enable")
  val printAddrs = collection.mutable.ArrayBuffer[Int]()
  val countAddrs = collection.mutable.ArrayBuffer[Int]()
  val channelWidth = if (p(HasDMAChannel)) io.dma.get.nastiXDataBits else io.ctrl.nastiXDataBits
  val printWidth = (printPort.elements foldLeft 56)(_ + _._2.getWidth - 1)
  val valid = (printPort.elements foldLeft false.B)( _ || _._2(0))
  val ps = printPort.elements.toSeq map (_._2 >> 1)
  val vs = printPort.elements.toSeq map (_._2(0))
  val data = Cat(Cat(ps.reverse), Cat(vs.reverse) | 0.U(8.W), cycles)
  /* FIXME */ if (p(HasDMAChannel)) assert(printWidth <= channelWidth)

  val prints = (0 until printWidth by channelWidth).zipWithIndex map { case (off, i) =>
    val width = channelWidth min (printWidth - off)
    val wires = Wire(Decoupled(UInt(width.W)))
    // val queue = Queue(wires, 8 * 1024)
    val queue = BRAMQueue(wires, 8 * 1024)
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
      io.dma.foreach({ dma =>
        val arQueue   = Queue(dma.ar, 10)
        val readBeats = RegInit(0.U(9.W))
        readBeats suggestName "readBeats"
        when(dma.r.fire()) {
          readBeats := Mux(dma.r.bits.last, 0.U, readBeats + 1.U)
        }

        queue.ready := dma.r.ready && arQueue.valid
        dma.r.valid := queue.valid && arQueue.valid
        dma.r.bits.data := queue.bits
        dma.r.bits.last := arQueue.bits.len === readBeats
        dma.r.bits.id   := arQueue.bits.id
        dma.r.bits.user := arQueue.bits.user
        dma.r.bits.resp := 0.U
        arQueue.ready := dma.r.fire() && dma.r.bits.last

        // No write
        dma.aw.ready := false.B
        dma.w.ready := false.B
        dma.b.valid := false.B
        dma.b.bits := DontCare
      })
    } else {
      printAddrs += attachDecoupledSource(queue, s"prints_data_$i")
    }
    wires.ready || !valid
  }
  fire := (prints foldLeft (io.hPort.toHost.hValid && io.tReset.valid))(_ && _)
  io.tReset.ready := fire
  io.hPort.toHost.hReady := fire
  // We don't generate tokens
  io.hPort.fromHost.hValid := true.B
  when (fire) {
    cycles := Mux(io.tReset.bits, 0.U, cycles + 1.U)
  }

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    sb.append(genComment("Print Widget"))
    sb.append(genMacro("PRINTS_NUM", UInt32(printPort.elements.size)))
    sb.append(genMacro("PRINTS_CHUNKS", UInt32(prints.size)))
    sb.append(genMacro("PRINTS_ENABLE", UInt32(base + enableAddr)))
    sb.append(genMacro("PRINTS_COUNT_ADDR", UInt32(base + countAddrs.head)))
    sb.append(genArray("PRINTS_WIDTHS", printPort.elements.toSeq map (x => UInt32(x._2.getWidth))))
    if (!p(HasDMAChannel)) {
      sb.append(genArray("PRINTS_DATA_ADDRS", printAddrs.toSeq map (x => UInt32(base + x))))
    } else {
      sb.append(genMacro("HAS_DMA_CHANNEL"))
    }
  }

  genCRFile()
}
