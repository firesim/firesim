//See LICENSE for license details
package firesim.bridges

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._

import testchipip.{TileTraceIO, TraceBundleWidths, SerializableTileTraceIO}

import midas.widgets._

/**
 * Blackbox that is instantiated in the target
 */
class DromajoBridge(widths: TraceBundleWidths) extends BlackBox
    with Bridge[HostPortIO[SerializableTileTraceIO], DromajoBridgeModule]
{
  val io = IO(new SerializableTileTraceIO(widths))
  val bridgeIO = HostPort(io)

  // give the Dromajo key to the GG module
  val constructorArg = Some(widths)

  // generate annotations to pass to GG
  generateAnnotations()
}

/**
 * Helper function to connect blackbox
 */
object DromajoBridge {
  def apply(tracedInsns: TileTraceIO)(implicit p: Parameters): DromajoBridge = {
    val b = Module(new DromajoBridge(tracedInsns.traceBundleWidths))
    b.io.trace := tracedInsns.asSerializableTileTrace
    b
  }
}

//*************************************************
//* GOLDEN GATE MODULE
//* This lives in the host (still runs on the FPGA)
//*************************************************

class DromajoBridgeModule(key: TraceBundleWidths)(implicit p: Parameters) extends BridgeModule[HostPortIO[SerializableTileTraceIO]]()(p)
    with StreamToHostCPU
{
  // CONSTANTS: DMA Parameters
  val toHostCPUQueueDepth = 6144

  lazy val module = new BridgeModuleImp(this) {

    // setup io
    val io = IO(new WidgetIO)
    val hPort = IO(HostPort(new SerializableTileTraceIO(key)))

    // helper to get number to round up to nearest multiple
    def roundUp(num: Int, mult: Int): Int = { (num/mult).ceil.toInt * mult }

    // get the traces
    val traces = hPort.hBits.trace.insns.map({ unmasked =>
      val masked = WireDefault(unmasked)
      masked.valid := unmasked.valid && !hPort.hBits.reset
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
      genConstructor(base, sb, "dromajo_t", "dromajo", Seq(
        UInt32(iaddrWidth),
        UInt32(insnWidth),
        UInt32(wdataWidth),
        UInt32(causeWidth),
        UInt32(tvalWidth),
        UInt32(numTraces)
      ))
    }

    // general information printout
    println(s"Dromajo Bridge Information")
    println(s"  Total Inst. Traces / Commit Width: ${numTraces}")
  }
}
