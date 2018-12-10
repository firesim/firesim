// See LICENSE for license details.

package midas
package widgets

import scala.collection.immutable.ListMap

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}


import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}

import junctions._

import midas.{PrintPorts}
import midas.core.{HostPort, DMANastiKey}


class PrintRecord(portType: firrtl.ir.BundleType, val formatString: String) extends Record {
  def regenLeafType(tpe: firrtl.ir.Type): Data = tpe match {
    case firrtl.ir.UIntType(width: firrtl.ir.IntWidth) => UInt(width.width.toInt.W)
    case firrtl.ir.SIntType(width: firrtl.ir.IntWidth) => SInt(width.width.toInt.W)
    case badType => throw new RuntimeException(s"Unexpected type in PrintBundle: ${badType}")
  }

  val args: Seq[(String, Data)] = portType.fields.collect({
    case firrtl.ir.Field(name, _, tpe) if name != "enable" => (name -> Output(regenLeafType(tpe)))
  })

  val enable = Output(Bool())

  val elements = ListMap((Seq("enable" -> enable) ++ args):_*)
  override def cloneType = new PrintRecord(portType, formatString).asInstanceOf[this.type]

  // Gets the bit position of each argument after the record has been flattened to a UInt
  def argumentOffsets() = args.foldLeft(Seq(enable.getWidth))({
      case (offsets, (_, data)) => data.getWidth +: offsets}).tail.reverse

  def argumentWidths(): Seq[Int] = args.map(_._2.getWidth)
}


class PrintRecordBag(prefix: String, printPorts: Seq[(firrtl.ir.Port, String)]) extends Record {
  val ports: Seq[(String, PrintRecord)] = printPorts.collect({
    case (firrtl.ir.Port(_, name, _, tpe @ firrtl.ir.BundleType(_)), formatString) =>
      name.stripPrefix(prefix) -> new PrintRecord(tpe, formatString)
  })

  val elements = ListMap(ports:_*)
  override def cloneType = new PrintRecordBag(prefix, printPorts).asInstanceOf[this.type]

  // Generates a Bool indicating if at least one Printf has it's enable set on this cycle
  def hasEnabledPrint(): Bool = elements.map(_._2.enable).foldLeft(false.B)(_ || _)
}

class PrintRecordEndpoint extends Endpoint {
  var initialized = false
  var printRecordGen: PrintRecordBag = new PrintRecordBag("dummy", Seq())

  def matchType(data: Data) = data match {
    case channel: PrintRecordBag =>
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

class PrintWidgetIO(private val record: PrintRecordBag)(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(record))
}

class PrintWidget(printRecord: PrintRecordBag)(implicit p: Parameters) extends EndpointWidget()(p)
    with UnidirectionalDMAToHostCPU {
  val io = IO(new PrintWidgetIO(printRecord))
  lazy val toHostCPUQueueDepth = 512 * 4 // 4 Ultrascale+ URAMs
  lazy val dmaSize = BigInt(512 * 4)

  val printPort = io.hPort.hBits
  val totalPrintWidth = printPort.getWidth
  /* FIXME */ assert(totalPrintWidth + 1 <= dma.nastiXDataBits)
  val valid = printPort.hasEnabledPrint
  val data = Cat(printPort.asUInt, valid) | 0.U(dma.nastiXDataBits.W)

  val tFireHelper = DecoupledHelper(io.hPort.toHost.hValid, io.tReset.valid, outgoingPCISdat.io.enq.ready)
  io.tReset.ready := tFireHelper.fire(io.tReset.valid)
  io.hPort.toHost.hReady := tFireHelper.fire(io.hPort.toHost.hValid)
  outgoingPCISdat.io.enq.bits := data
  outgoingPCISdat.io.enq.valid := tFireHelper.fire(outgoingPCISdat.io.enq.ready)

  // We don't generate tokens
  io.hPort.fromHost.hValid := true.B

  // The LSB corresponding to the enable bit of the print
  val reservedBits = 1 // Just print-wide enable
  val widths = (printRecord.elements.map(_._2.getWidth))

  // C-types for emission
  val baseOffsets = widths.foldLeft(Seq(UInt32(reservedBits)))({ case (offsets, width) => 
    UInt32(offsets.head.value + width) +: offsets}).tail.reverse

  val argumentCounts  = printRecord.ports.map(_._2.args.size).map(UInt32(_))
  val argumentWidths  = printRecord.ports.flatMap(_._2.argumentWidths).map(UInt32(_))
  val argumentOffsets = printRecord.ports.map(_._2.argumentOffsets.map(UInt32(_)))
  val formatStrings   = printRecord.ports.map(_._2.formatString).map(CStrLit)

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    val headerWidgetName = getWName.toUpperCase
    super.genHeader(base, sb)
    sb.append(genConstStatic(s"${headerWidgetName}_print_count", UInt32(printRecord.ports.size)))
    sb.append(genArray(s"${headerWidgetName}_print_offsets", baseOffsets))
    sb.append(genArray(s"${headerWidgetName}_format_strings", formatStrings))
    sb.append(genArray(s"${headerWidgetName}_argument_counts", argumentCounts))
    sb.append(genArray(s"${headerWidgetName}_argument_widths", argumentWidths))
  }
  genCRFile()
}
