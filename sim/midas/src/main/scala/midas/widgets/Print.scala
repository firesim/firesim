// See LICENSE for license details.

package midas.widgets

import scala.collection.immutable.ListMap

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}

import midas.core.{DMANastiKey}

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

class PrintBridgeModule(prefix: String, printPorts: Seq[(firrtl.ir.Port, String)])(implicit p: Parameters)
    extends BridgeModule[HostPortIO[PrintRecordBag]]()(p) with UnidirectionalDMAToHostCPU {
  val io = IO(new WidgetIO())
  val hPort = IO(HostPort(Flipped(new PrintRecordBag(prefix, printPorts))))

  lazy val toHostCPUQueueDepth = 6144 // 12 Ultrascale+ URAMs
  lazy val dmaSize = BigInt(dmaBytes * toHostCPUQueueDepth)

  val printPort = hPort.hBits
  // Pick a padded token width that's a power of 2 bytes, including enable bit
  val reservedBits = 1 // Just print-wide enable
  val pow2Bits = math.max(8, 1 << log2Ceil(printPort.getWidth + reservedBits))
  val idleCycleBits = math.min(16, pow2Bits) - reservedBits
  // The number of tokens that fill a single DMA beat
  val packingRatio = if (pow2Bits < dma.nastiXDataBits) Some(dma.nastiXDataBits/pow2Bits) else None

  // PROGRAMMABLE REGISTERS
  val startCycleL = genWOReg(Wire(UInt(32.W)), "startCycleL")
  val startCycleH = genWOReg(Wire(UInt(32.W)), "startCycleH")
  val endCycleL   = genWOReg(Wire(UInt(32.W)), "endCycleL")
  val endCycleH   = genWOReg(Wire(UInt(32.W)), "endCycleH")
  // Set after the start and end counters have been initialized; can begin consuming tokens
  val doneInit    = genWORegInit(Wire(Bool()), "doneInit", false.B)
  // Set at the end of simulation to flush an incomplete narrow packet
  // Unused if printTokens >= DMA width
  val flushNarrowPacket = WireInit(false.B)
  Pulsify(genWORegInit(flushNarrowPacket, "flushNarrowPacket", false.B),
          pulseLength = packingRatio.getOrElse(1))


  // TOKEN CONTROL LOGIC
  val startCycle = Cat(startCycleH, startCycleL)
  val endCycle = Cat(endCycleH, endCycleL)
  val currentCycle = RegInit(0.U(64.W))
  val idleCycles =   RegInit(0.U(idleCycleBits.W))

  // Gate passing tokens of to software if we are not in the region of interest
  val enable = (startCycle <= currentCycle) && (currentCycle <= endCycle)
  val bufferReady = Wire(Bool())
  val readyToEnqToken = !enable || bufferReady

  // Token control
  val dummyPredicate = true.B
  val tFireHelper = DecoupledHelper(doneInit,
                                    hPort.toHost.hValid,
                                    readyToEnqToken,
                                    ~flushNarrowPacket,
                                    dummyPredicate)
  // Hack: include toHost.hValid to prevent individual wire channels from dequeuing before
  // all sub-channels have valid tokens
  hPort.toHost.hReady := tFireHelper.fire
  // We don't generate tokens
  hPort.fromHost.hValid := true.B
  val tFire = tFireHelper.fire(dummyPredicate)

  when (tFire) {
    currentCycle := currentCycle + 1.U
  }

  // PAYLOAD HANDLING
  // TODO: Gating the prints using reset should be done by the transformation,
  // (using a predicate carried by the annotation(?))
  val valid = printPort.hasEnabledPrint
  val data = Cat(printPort.asUInt, valid) | 0.U(pow2Bits.W)

  // Delay the valid token by a cycle so that we can first enqueue an idle-cycle-encoded token
  val dataPipe = Module(new Queue(data.cloneType, 1, pipe = true))
  dataPipe.io.enq.bits  := data
  dataPipe.io.enq.valid := tFire && valid && enable
  dataPipe.io.deq.ready := bufferReady

  val idleCyclesRollover = idleCycles.andR
  when ((tFire && valid && enable) || flushNarrowPacket) {
    idleCycles := 0.U
  }.elsewhen(tFire && enable) {
    idleCycles := Mux(idleCyclesRollover, 1.U, idleCycles + 1.U)
  }

  val printToken = Mux(dataPipe.io.deq.valid,
                       dataPipe.io.deq.bits,
                       Cat(idleCycles, 0.U(reservedBits.W)))


  val tokenValid = tFireHelper.fire(readyToEnqToken) && enable &&
                      (valid && idleCycles =/= 0.U || idleCyclesRollover) ||
                   dataPipe.io.deq.valid

  if (pow2Bits == dma.nastiXDataBits) {
    outgoingPCISdat.io.enq.bits := printToken
    outgoingPCISdat.io.enq.valid := tokenValid
    bufferReady := outgoingPCISdat.io.enq.ready
  } else {
    // Pack narrow or serialize wide tokens if they do not match the size of the DMA bus
    val mwFifoDepth = math.max(1, 1 * pow2Bits/dma.nastiXDataBits)
    val widthAdapter = Module(new junctions.MultiWidthFifo(pow2Bits, dma.nastiXDataBits, mwFifoDepth))
    outgoingPCISdat.io.enq <> widthAdapter.io.out
    // If packing, we need to be able to flush an incomplete beat to the FIFO,
    // or we'll lose [0, packingRatio - 1] tokens at the end of simulation
    packingRatio match {
      case None =>
        widthAdapter.io.in.bits := printToken
        widthAdapter.io.in.valid := tokenValid
      case Some(_) =>
        widthAdapter.io.in.bits :=  printToken
        widthAdapter.io.in.valid := tokenValid || flushNarrowPacket
    }
    bufferReady := widthAdapter.io.in.ready
  }

  // HEADER GENERATION
  // The LSB corresponding to the enable bit of the print
  val widths = (printPort.elements.map(_._2.getWidth))

  // C-types for emission
  val baseOffsets = widths.foldLeft(Seq(UInt32(reservedBits)))({ case (offsets, width) => 
    UInt32(offsets.head.value + width) +: offsets}).tail.reverse

  val argumentCounts  = printPort.ports.map(_._2.args.size).map(UInt32(_))
  val argumentWidths  = printPort.ports.flatMap(_._2.argumentWidths).map(UInt32(_))
  val argumentOffsets = printPort.ports.map(_._2.argumentOffsets.map(UInt32(_)))
  val formatStrings   = printPort.ports.map(_._2.formatString).map(CStrLit)

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    val headerWidgetName = getWName.toUpperCase
    super.genHeader(base, sb)
    sb.append(genConstStatic(s"${headerWidgetName}_print_count", UInt32(printPort.ports.size)))
    sb.append(genConstStatic(s"${headerWidgetName}_token_bytes", UInt32(pow2Bits / 8)))
    sb.append(genConstStatic(s"${headerWidgetName}_idle_cycles_mask",
                             UInt32(((1 << idleCycleBits) - 1) << reservedBits)))
    sb.append(genArray(s"${headerWidgetName}_print_offsets", baseOffsets))
    sb.append(genArray(s"${headerWidgetName}_format_strings", formatStrings))
    sb.append(genArray(s"${headerWidgetName}_argument_counts", argumentCounts))
    sb.append(genArray(s"${headerWidgetName}_argument_widths", argumentWidths))
  }
  genCRFile()
}
