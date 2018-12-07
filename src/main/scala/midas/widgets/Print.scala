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

class PrintRecord(argTypes: Seq[firrtl.ir.Type]) extends Record {
  def regenLeafType(tpe: firrtl.ir.Type): Data = tpe match {
    case firrtl.ir.UIntType(width: firrtl.ir.IntWidth) => UInt(width.width.toInt.W)
    case firrtl.ir.SIntType(width: firrtl.ir.IntWidth) => SInt(width.width.toInt.W)
    case badType => throw new RuntimeException(s"Unexpected type in PrintBundle: ${badType}")
  }
  val enable = Output(Bool())
  val args: Seq[(String, Data)] =argTypes.zipWithIndex.map({ case (tpe, idx) =>
    s"args_${idx}" -> Output(regenLeafType(tpe))
  })
  val elements = ListMap((Seq("enable" -> enable) ++ args):_*)
  override def cloneType = new PrintRecord(argTypes).asInstanceOf[this.type]
}


class PrintRecordBag(prefix: String, printPorts: Seq[firrtl.ir.Port]) extends Record {
  val ports: Seq[(String, PrintRecord)] = printPorts.collect({ 
      case firrtl.ir.Port(_, name, _, firrtl.ir.BundleType(fields)) =>
    val argTypes = fields.flatMap({_  match {
      case firrtl.ir.Field(name, _, _) if name == "enable" => None
      case firrtl.ir.Field(_, _, tpe) => Some(tpe)
    }})
    name.stripPrefix(prefix) -> new PrintRecord(argTypes)
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

  //override def genHeader(base: BigInt, sb: StringBuilder) {
  //  import CppGenerationUtils._
  //  sb.append(genComment("Print Widget"))
  //  sb.append(genMacro("PRINTS_NUM", UInt32(printPort.elements.size)))
  //  sb.append(genMacro("PRINTS_CHUNKS", UInt32(prints.size)))
  //  sb.append(genMacro("PRINTS_ENABLE", UInt32(base + enableAddr)))
  //  sb.append(genMacro("PRINTS_COUNT_ADDR", UInt32(base + countAddrs.head)))
  //  sb.append(genArray("PRINTS_WIDTHS", printPort.elements.toSeq map (x => UInt32(x._2.getWidth))))
  //  if (!p(HasDMAChannel)) {
  //    sb.append(genArray("PRINTS_DATA_ADDRS", printAddrs.toSeq map (x => UInt32(base + x))))
  //  } else {
  //    sb.append(genMacro("HAS_DMA_CHANNEL"))
  //  }
  //}
  genCRFile()
}
