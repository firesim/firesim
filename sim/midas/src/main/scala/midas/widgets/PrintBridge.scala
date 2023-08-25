// See LICENSE for license details.

package midas.widgets

import scala.collection.immutable.ListMap

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}

class PrintRecord(portType: firrtl.ir.BundleType, val formatString: String) extends Record {
  def regenLeafType(tpe: firrtl.ir.Type): Data = tpe match {
    case firrtl.ir.UIntType(width: firrtl.ir.IntWidth) => UInt(width.width.toInt.W)
    case firrtl.ir.SIntType(width: firrtl.ir.IntWidth) => SInt(width.width.toInt.W)
    case badType => throw new RuntimeException(s"Unexpected type in PrintBundle: ${badType}")
  }

  val args: Seq[(String, Data)] = portType.fields.collect({
    case firrtl.ir.Field(name, _, tpe) if name != "enable" && name != "clock" =>
      (name -> Output(regenLeafType(tpe)))
  })

  val enable = Output(Bool())

  val elements = ListMap((Seq("enable" -> enable) ++ args):_*)

  // Gets the bit position of each argument after the record has been flattened to a UInt
  def argumentOffsets() = args.foldLeft(Seq(enable.getWidth))({
      case (offsets, (_, data)) => data.getWidth +: offsets}).tail.reverse

  def argumentWidths(): Seq[Int] = args.map(_._2.getWidth)
}


class PrintRecordBag(resetPortName: String, printPorts: Seq[(firrtl.ir.Port, String)]) extends Record {
  val underGlobalReset = Input(Bool())
  val printRecords: Seq[(String, PrintRecord)] = printPorts.collect({
    case (firrtl.ir.Port(_, name, _, tpe @ firrtl.ir.BundleType(_)), formatString) =>
      name -> Input(new PrintRecord(tpe, formatString))
  })

  val elements = ListMap(((resetPortName -> underGlobalReset) +: printRecords):_*)

  // Generates a Bool indicating if at least one Printf has it's enable set on this cycle
  def hasEnabledPrint(): Bool = printRecords.map(_._2.enable).foldLeft(false.B)(_ || _) && !underGlobalReset
}

case class PrintPort(name: String, ports: Seq[(String, String)], format: String)

case class PrintBridgeParameters(resetPortName: String, printPorts: Seq[PrintPort])

class PrintBridgeModule(key: PrintBridgeParameters)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[PrintRecordBag]]()(p) with StreamToHostCPU {

  //  The fewest number of BRAMS that produces a memory that is 512b wide.(8 X 32Kb BRAM)
  override val toHostCPUQueueDepth = 6144 // 12 Ultrascale+ URAMs

  val resetPortName = key.resetPortName
  val printPorts = key.printPorts.map({
    case PrintPort(printName, ports, format) => {
      val fields = firrtl.ir.BundleType(ports.map({ case (name, ty) => firrtl.ir.Field(name, firrtl.ir.Default, firrtl.Parser.parseType(ty)) }))
      val port = firrtl.ir.Port(firrtl.ir.NoInfo, printName, firrtl.ir.Output, fields)
      (port, format)
    }
  })

  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(HostPort(new PrintRecordBag(resetPortName, printPorts)))

    val printPort = hPort.hBits
    // Pick a padded token width that's a power of 2 bytes, including enable bit
    val reservedBits = 1 // Just print-wide enable
    val pow2Bits = math.max(8, 1 << log2Ceil(printPort.getWidth + reservedBits))
    val idleCycleBits = math.min(16, pow2Bits) - reservedBits
    // The number of tokens that fill a single DMA beat
    val packingRatio = if (pow2Bits < BridgeStreamConstants.streamWidthBits) Some(BridgeStreamConstants.streamWidthBits/pow2Bits) else None

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
    hPort.toHost.hReady := tFireHelper.fire()
    // We don't generate tokens
    hPort.fromHost.hValid := true.B
    val tFire = tFireHelper.fire(dummyPredicate)

    when (tFire) {
      currentCycle := currentCycle + 1.U
    }

    // PAYLOAD HANDLING
    val valid = printPort.hasEnabledPrint()
    val printsAsUInt = Cat(printPort.printRecords.map(_._2.asUInt).reverse)
    val data = Cat(printsAsUInt, valid) | 0.U(pow2Bits.W)

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

    if (pow2Bits == BridgeStreamConstants.streamWidthBits) {
      streamEnq.bits := printToken
      streamEnq.valid := tokenValid
      bufferReady := streamEnq.ready
    } else {
      // Pack narrow or serialize wide tokens if they do not match the size of the DMA bus
      val mwFifoDepth = math.max(1, 1 * pow2Bits/BridgeStreamConstants.streamWidthBits)
      val widthAdapter = Module(new junctions.MultiWidthFifo(pow2Bits, BridgeStreamConstants.streamWidthBits, mwFifoDepth))
      streamEnq <> widthAdapter.io.out
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

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      val offsets = printPort.printRecords.map(_._2.getWidth).foldLeft(Seq(UInt32(reservedBits)))({ case (offsets, width) =>
        UInt32(offsets.head.value + width) +: offsets}).tail.reverse

      genConstructor(
          base,
          sb,
          "synthesized_prints_t",
          "synthesized_prints",
          Seq(
              StdVector("synthesized_prints_t::Print",
                printPort.printRecords.zip(offsets).map({ case ((_, p), offset) =>
                  CppStruct("synthesized_prints_t::Print", Seq(
                    "print_offset" -> offset,
                    "format_string" -> CStrLit(p.formatString),
                    "argument_widths" -> StdVector("unsigned", p.argumentWidths.map(UInt32(_)))
                  ))
                })
              ),
              UInt32(pow2Bits / 8),
              UInt32(((1 << idleCycleBits) - 1) << reservedBits),
              UInt32(toHostStreamIdx),
              UInt32(toHostCPUQueueDepth),
              Verbatim(clockDomainInfo.toC)
          ),
          hasStreams = true,
          hasMMIOAddrMap = false
      )
    }
    genCRFile()
  }
}
