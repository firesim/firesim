//See LICENSE for license details
package firesim.bridges

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

import testchipip.{SerializableTileTraceIO, SpikeCosimConfig, TileTraceIO, TraceBundleWidths}

import midas.widgets._

case class CospikeBridgeParams(
  widths: TraceBundleWidths,
  hartid: Int,
  cfg:    SpikeCosimConfig,
)

class CospikeTargetIO(widths: TraceBundleWidths) extends Bundle {
  val trace = Input(new SerializableTileTraceIO(widths))
}

/** Blackbox that is instantiated in the target
  */
class CospikeBridge(params: CospikeBridgeParams)
    extends BlackBox
    with Bridge[HostPortIO[CospikeTargetIO], CospikeBridgeModule] {
  val io       = IO(new CospikeTargetIO(params.widths))
  val bridgeIO = HostPort(io)

  // give the Cospike params to the GG module
  val constructorArg = Some(params)

  // generate annotations to pass to GG
  generateAnnotations()
}

/** Helper function to connect blackbox
  */
object CospikeBridge {
  def apply(trace: TileTraceIO, hartid: Int, cfg: SpikeCosimConfig) = {
    val params = new CospikeBridgeParams(trace.traceBundleWidths, hartid, cfg)
    val cosim  = withClockAndReset(trace.clock, trace.reset) {
      Module(new CospikeBridge(params))
    }
    cosim.io.trace.trace.insns.map(t => {
      t       := DontCare
      t.valid := false.B
    })
    cosim.io.trace := trace.asSerializableTileTrace
    cosim
  }
}

//*************************************************
//* GOLDEN GATE MODULE
//* This lives in the host (still runs on the FPGA)
//*************************************************

class CospikeBridgeModule(params: CospikeBridgeParams)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[CospikeTargetIO]]()(p)
    with StreamToHostCPU {
  // CONSTANTS: DMA Parameters
  val toHostCPUQueueDepth = 6144

  lazy val module = new BridgeModuleImp(this) {

    // setup io
    val io    = IO(new WidgetIO)
    val hPort = IO(HostPort(new CospikeTargetIO(params.widths)))

    // helper to get number to round up to nearest multiple
    def roundUp(num: Int, mult: Int): Int = { (num.toFloat / mult).ceil.toInt * mult }

    // get the traces
    val traces             = hPort.hBits.trace.trace.insns.map({ unmasked =>
      val masked = WireDefault(unmasked)
      masked.valid := unmasked.valid && !hPort.hBits.trace.reset
      masked
    })
    private val iaddrWidth = roundUp(traces.map(_.iaddr.getWidth).max, 8)
    private val insnWidth  = roundUp(traces.map(_.insn.getWidth).max, 8)
    private val causeWidth = roundUp(traces.map(_.cause.getWidth).max, 8)
    private val wdataWidth = roundUp(traces.map(t => if (t.wdata.isDefined) t.wdata.get.getWidth else 0).max, 8)

    // hack since for some reason padding a bool doesn't work...
    def boolPad(in: Bool, size: Int): UInt = {
      val temp = Wire(UInt(size.W))
      temp := in.asUInt
      temp
    }

    // matches order of TracedInstruction in CSR.scala
    val paddedTraces = traces.map { trace =>
      val pre_cat = Cat(
        trace.cause.pad(causeWidth),
        boolPad(trace.interrupt, 8),
        boolPad(trace.exception, 8),
        trace.priv.asUInt.pad(8),
        trace.insn.pad(insnWidth),
        trace.iaddr.pad(iaddrWidth),
        boolPad(trace.valid, 8),
      )

      if (wdataWidth == 0) {
        pre_cat
      } else {
        Cat(trace.wdata.get.pad(wdataWidth), pre_cat)
      }
    }

    val maxTraceSize  = paddedTraces.map(t => t.getWidth).max
    val outDataSzBits = streamEnq.bits.getWidth

    // constant
    // TODO: match tracerv in supporting commitWidth > 2
    val totalTracesPerToken = 2 // minTraceSz==190b so round up to nearest is 256b
    // constant

    val bitsPerTrace = outDataSzBits / totalTracesPerToken

    require(maxTraceSize < bitsPerTrace, "All instruction trace bits (i.e. valid, pc, instBits...) must fit in 256b")

    // how many traces being sent over
    val numTraces      = traces.size
    // num tokens needed to display full set of instructions from one cycle
    val numTokenForAll = ((numTraces - 1) / totalTracesPerToken) + 1

    // only inc the counter when the something is sent (this implies that the input is valid and output is avail on the other side)
    val counterFire = streamEnq.fire
    val (cnt, wrap) = Counter(counterFire, numTokenForAll)

    val paddedTracesAligned   = paddedTraces.map(t => t.asUInt.pad(bitsPerTrace))
    val paddedTracesTruncated = if (numTraces == 1) {
      (VecInit(paddedTracesAligned).asUInt >> (outDataSzBits.U * cnt))
    } else {
      (VecInit(paddedTracesAligned).asUInt >> (outDataSzBits.U * cnt))(outDataSzBits - 1, 0)
    }

    streamEnq.valid := hPort.toHost.hValid
    streamEnq.bits  := paddedTracesTruncated

    // tell the host that you are ready to get more
    hPort.toHost.hReady := streamEnq.ready && wrap

    // This is uni-directional. We don't drive tokens back to the target.
    hPort.fromHost.hValid := true.B

    genCRFile()

    // modify the output header file
    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
        base,
        sb,
        "cospike_t",
        "cospike",
        Seq(
          UInt32(iaddrWidth),
          UInt32(insnWidth),
          UInt32(causeWidth),
          UInt32(wdataWidth),
          UInt32(numTraces),
          CStrLit(params.cfg.isa),
          UInt32(params.cfg.vlen),
          CStrLit(params.cfg.priv),
          UInt32(params.cfg.pmpregions),
          UInt64(params.cfg.mem0_base),
          UInt64(params.cfg.mem0_size),
          UInt32(params.cfg.nharts),
          CStrLit(params.cfg.bootrom),
          UInt32(params.hartid),
          UInt32(toHostStreamIdx),
          UInt32(toHostCPUQueueDepth),
        ),
        hasStreams = true,
      )
    }

    // general information printout
    println(s"Cospike Bridge Information")
    println(s"  Total Inst. Traces / Commit Width: ${numTraces}")
  }
}
