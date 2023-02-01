//See LICENSE for license details
package firesim.bridges

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util._

import testchipip.{TileTraceIO, TracedInstructionWidths}

import midas.widgets._

//******
//* MISC
//******

case class DromajoKey(
  insnWidths: TracedInstructionWidths, // Widths of variable length fields in each TI
  vecSizes: Int // The number of insns in each vec (= max insns retired at that core)
)

//*************
//* TARGET LAND
//*************

class DromajoTargetIO(insnWidths: TracedInstructionWidths, numInsns: Int) extends Bundle {
    val trace = Input(new TileTraceIO(insnWidths, numInsns))
}

/**
 * Blackbox that is instantiated in the target
 */
class DromajoBridge(insnWidths: TracedInstructionWidths, numInsns: Int) extends BlackBox
    with Bridge[HostPortIO[DromajoTargetIO], DromajoBridgeModule]
{
  val io = IO(new DromajoTargetIO(insnWidths, numInsns))
  val bridgeIO = HostPort(io)

  // give the Dromajo key to the GG module
  val constructorArg = Some(DromajoKey(insnWidths, numInsns))

  // generate annotations to pass to GG
  generateAnnotations()
}

/**
 * Helper function to connect blackbox
 */
object DromajoBridge {
  def apply(tracedInsns: TileTraceIO)(implicit p: Parameters): DromajoBridge = {
    val b = Module(new DromajoBridge(tracedInsns.insnWidths, tracedInsns.numInsns))
    b.io.trace := tracedInsns
    b
  }
}

//*************************************************
//* GOLDEN GATE MODULE
//* This lives in the host (still runs on the FPGA)
//*************************************************

class DromajoBridgeModule(key: DromajoKey)(implicit p: Parameters) extends BridgeModule[HostPortIO[DromajoTargetIO]]()(p)
    with StreamToHostCPU
{
  // CONSTANTS: DMA Parameters
  val toHostCPUQueueDepth = TokenQueueConsts.TOKEN_QUEUE_DEPTH

  lazy val module = new BridgeModuleImp(this) {

    // setup io
    val io = IO(new WidgetIO)
    val hPort = IO(HostPort(new DromajoTargetIO(key.insnWidths, key.vecSizes)))

    // helper to get number to round up to nearest multiple
    def roundUp(num: Int, mult: Int): Int = { (num/mult).ceil.toInt * mult }

    // get the traces
    val traces = hPort.hBits.trace.insns.map({ unmasked =>
      val masked = WireDefault(unmasked)
      masked.valid := unmasked.valid && !hPort.hBits.trace.reset
      masked
    })
    private val iaddrWidth = roundUp(traces.map(_.iaddr.getWidth).max, 8)
    private val insnWidth  = roundUp(traces.map(_.insn.getWidth).max, 8)
    private val wdataWidth = roundUp(traces.map(_.wdata.get.getWidth).max, 8)
    private val causeWidth = roundUp(traces.map(_.cause.getWidth).max, 8)
    private val tvalWidth  = roundUp(traces.map(_.tval.getWidth).max, 8)

    // hack since for some reason padding a bool doesn't work...
    def boolPad(in: Bool, size: Int): UInt = {
      val temp = Wire(UInt(size.W))
      temp := in.asUInt
      temp
    }

    val paddedTraces = traces.map { trace =>
      Cat(
        trace.tval.pad(tvalWidth),
        trace.cause.pad(causeWidth),
        boolPad(trace.interrupt, 8),
        boolPad(trace.exception, 8),
        trace.priv.asUInt.pad(8),
        trace.wdata.get.pad(wdataWidth),
        trace.insn.pad(insnWidth),
        trace.iaddr.pad(iaddrWidth),
        boolPad(trace.valid, 8)
      )
    }

    val maxTraceSize = paddedTraces.map(t => t.getWidth).max
    val outDataSzBits = streamEnq.bits.getWidth

    // constant
    val totalTracesPerToken = 2 // minTraceSz==190b so round up to nearest is 256b
    // constant

    require(maxTraceSize < (outDataSzBits / totalTracesPerToken), "All instruction trace bits (i.e. valid, pc, instBits...) must fit in 256b")

    // how many traces being sent over
    val numTraces = traces.size
    // num tokens needed to display full set of instructions from one cycle
    val numTokenForAll = ((numTraces - 1) / totalTracesPerToken) + 1

    // only inc the counter when the something is sent (this implies that the input is valid and output is avail on the other side)
    val counterFire = streamEnq.fire
    val (cnt, wrap) = Counter(counterFire, numTokenForAll)

    val paddedTracesAligned = paddedTraces.map(t => t.asUInt.pad(outDataSzBits/totalTracesPerToken))
    val paddedTracesTruncated = if (numTraces == 1) {
      (paddedTracesAligned.asUInt >> (outDataSzBits.U * cnt))
    } else {
      (paddedTracesAligned.asUInt >> (outDataSzBits.U * cnt))(outDataSzBits-1, 0)
    }

    streamEnq.valid := hPort.toHost.hValid
    streamEnq.bits := paddedTracesTruncated

    // tell the host that you are ready to get more
    hPort.toHost.hReady := streamEnq.ready && wrap

    // This is uni-directional. We don't drive tokens back to the target.
    hPort.fromHost.hValid := true.B

    genCRFile()

    // modify the output header file
    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      super.genHeader(base, memoryRegions, sb)

      sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_iaddr_width", UInt32(iaddrWidth)))
      sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_insn_width", UInt32(insnWidth)))
      sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_wdata_width", UInt32(wdataWidth)))
      sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_cause_width", UInt32(causeWidth)))
      sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_tval_width", UInt32(tvalWidth)))
      sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_num_traces", UInt32(numTraces)))
    }

    // general information printout
    println(s"Dromajo Bridge Information")
    println(s"  Total Inst. Traces / Commit Width: ${numTraces}")
  }
}
