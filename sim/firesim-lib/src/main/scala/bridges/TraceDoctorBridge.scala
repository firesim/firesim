//See LICENSE for license details
package firesim.bridges

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.targetutils.TriggerSink
import midas.widgets._
import testchipip.TileTraceDoctorIO

case class TraceDoctorKey(traceWidth: Int)

class TraceDoctorTargetIO(val traceWidth : Int) extends Bundle {
  val trace = Input(new TileTraceDoctorIO(traceWidth))
}

class TraceDoctorBridge(val traceWidth: Int) extends BlackBox
    with Bridge[HostPortIO[TraceDoctorTargetIO], TraceDoctorBridgeModule] {
  val io = IO(new TraceDoctorTargetIO(traceWidth))
  val bridgeIO = HostPort(io)
  val constructorArg = Some(TraceDoctorKey(traceWidth))
  generateAnnotations()
}

object TraceDoctorBridge {
  def apply(tracedoctor: TileTraceDoctorIO)(implicit p:Parameters): TraceDoctorBridge = {
    val ep = Module(new TraceDoctorBridge(tracedoctor.traceWidth))
    ep.io.trace := tracedoctor
    ep
  }
}

class TraceDoctorBridgeModule(key: TraceDoctorKey)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[TraceDoctorTargetIO]]()(p)
    with StreamToHostCPU {

  val toHostCPUQueueDepth  = TokenQueueConsts.TOKEN_QUEUE_DEPTH

  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO)
    val hPort = IO(HostPort(new TraceDoctorTargetIO(key.traceWidth)))

    val initDone = genWORegInit(Wire(Bool()), "initDone", false.B)
    val traceEnable = genWORegInit(Wire(Bool()), "traceEnable", false.B)

    // Trigger Selector
    val triggerSelector = RegInit(0.U((p(CtrlNastiKey).dataBits).W))
    attach(triggerSelector, "triggerSelector", WriteOnly)

    // Mask off ready samples when under reset
    val trace = hPort.hBits.trace.data
    val traceValid = trace.valid && !hPort.hBits.trace.reset

    // Connect trigger
    val trigger = MuxLookup(triggerSelector, false.B, Seq(
      0.U -> true.B,
      1.U -> hPort.hBits.trace.tracerVTrigger
    ))

    val traceOut = initDone && traceEnable && traceValid && trigger

    // Width of the trace vector
    val traceWidth = trace.bits.getWidth
    // Width of one token as defined by the DMA
    val discreteDmaWidth = TokenQueueConsts.BIG_TOKEN_WIDTH
    // How many tokens we need to trace out the bit vector, at least one for DMA sanity
    val tokensPerTrace = math.max((traceWidth + discreteDmaWidth - 1) / discreteDmaWidth, 1)

    // Bridge DMA Parameters
    lazy val dmaSize = BigInt((discreteDmaWidth / 8) * TokenQueueConsts.TOKEN_QUEUE_DEPTH)

    // TODO: the commented out changes below show how multi-token transfers would work
    // However they show a bad performance for yet unknown reasons in terms of FPGA synth
    // timings -- verilator shows expected results
    // for now we limit us to 512 bits with an assert.

    assert(tokensPerTrace == 1)

    // State machine that controls which token we are sending and whether we are finished
    // val tokenCounter = new Counter(tokensPerTrace)
    // val readyNextTrace = WireInit(true.B)
    // when (streamEnq.fire()) {
    //  readyNextTrace := tokenCounter.inc()
    // }

    println( "TraceDoctorBridgeModule")
    println(s"    traceWidth      ${traceWidth}")
    println(s"    dmaTokenWidth   ${discreteDmaWidth}")
    println(s"    requiredTokens  {")
    for (i <- 0 until tokensPerTrace)  {
      val from = ((i + 1) * discreteDmaWidth) - 1
      val to   = i * discreteDmaWidth
      println(s"        ${i} -> traceBits(${from}, ${to})")
    }
    println( "    }")
    println( "")

    // val paddedTrace = trace.bits.asUInt().pad(tokensPerTrace * discreteDmaWidth)
    // val paddedTraceSeq = for (i <- 0 until tokensPerTrace) yield {
    //   i.U -> paddedTrace(((i + 1) * discreteDmaWidth) - 1, i * discreteDmaWidth)
    // }

    // streamEnq.valid := hPort.toHost.hValid && traceOut
    // streamEnq.bits := MuxLookup(tokenCounter.value , 0.U, paddedTraceSeq)

    // hPort.toHost.hReady := initDone && streamEnq.ready && readyNextTrace

    streamEnq.valid := hPort.toHost.hValid && traceOut
    streamEnq.bits := trace.bits.asUInt.pad(discreteDmaWidth)

    hPort.toHost.hReady := initDone && streamEnq.ready
    hPort.fromHost.hValid := true.B

    genCRFile()
    override def genHeader(base: BigInt, sb: StringBuilder) {
      import CppGenerationUtils._
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, sb)
      sb.append(genConstStatic(s"${headerWidgetName}_queue_depth", UInt32(TokenQueueConsts.TOKEN_QUEUE_DEPTH)))
      sb.append(genConstStatic(s"${headerWidgetName}_token_width", UInt32(discreteDmaWidth)))
      sb.append(genConstStatic(s"${headerWidgetName}_trace_width", UInt32(traceWidth)))
      emitClockDomainInfo(headerWidgetName, sb)
    }
  }
}
