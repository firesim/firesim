//See LICENSE for license details

package midas.widgets

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters


/**
  * The [[ResetPulseBridge]] drives a bool pulse from time zero for a
  * runtime-configurable number of cycles. These are its elaboration-time parameters.
  *
  * @param activeHigh When true, reset is initially set at time 0.
  * @param defaultPulseLength The number of cycles the reset is held at the time 0 value.
  * @param maxPulseLength The maximum runtime-configurable pulse length that the bridge will support.
  */
case class ResetPulseBridgeParameters(
    activeHigh: Boolean = true,
    defaultPulseLength: Int = 50,
    maxPulseLength: Int = 1023) {
  require(defaultPulseLength <= maxPulseLength)
}

class ResetPulseBridgeTargetIO extends Bundle {
  val clock = Input(Clock())
  val reset = Output(Bool())
}

/**
  * The host-side interface. This bridge has single channel with a bool
  * payload. [[ChannelizedHostPortIO.OutputChannel]] associates the reset on
  * the target side IF with a channel named reset on the host-side
  * BridgeModule (determined by reflection, since we're extending Bundle).
  *
  * @param targetIO A reference to the bound target-side interface. This need
  *        only be a hardware type during target-side instantiation, so that the
  *        direction of the channel can be properly checked, and the correct
  *        annotation can be emitted. During host-side instantiation, the unbound
  *        default is used --- this is OK because generateAnnotations() will not be
  *        called.
  *
  *        NB: This !MUST! be a private val when extending bundle, otherwise you'll get
  *        some extra elements in your host-side IF.
  */
class ResetPulseBridgeHostIO(private val targetIO: ResetPulseBridgeTargetIO = new ResetPulseBridgeTargetIO)
    extends Bundle with ChannelizedHostPortIO {
  def targetClockRef = targetIO.clock
  val reset = OutputChannel(targetIO.reset)
}

class ResetPulseBridge(params: ResetPulseBridgeParameters)
    extends BlackBox with Bridge[ResetPulseBridgeHostIO, ResetPulseBridgeModule] {
  val io = IO(new ResetPulseBridgeTargetIO)
  val bridgeIO = new ResetPulseBridgeHostIO(io)
  val constructorArg = Some(params)
  generateAnnotations()
}

class ResetPulseBridgeModule(cfg: ResetPulseBridgeParameters)(implicit p: Parameters)
    extends BridgeModule[ResetPulseBridgeHostIO]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(new ResetPulseBridgeHostIO())

    val remainingPulseLength = genWOReg(Wire(UInt(log2Ceil(cfg.maxPulseLength + 1).W)), "pulseLength")
    val pulseComplete = remainingPulseLength === 0.U
    val doneInit = genWORegInit(Wire(Bool()), "doneInit", false.B)

    hPort.reset.valid := doneInit
    hPort.reset.bits := pulseComplete ^ cfg.activeHigh.B

    when (hPort.reset.fire) {
      remainingPulseLength := Mux(pulseComplete, 0.U, remainingPulseLength - 1.U)
    }

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      import CppGenerationUtils._
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, memoryRegions, sb)
      sb.append(genConstStatic(s"${headerWidgetName}_max_pulse_length", UInt32(cfg.maxPulseLength)))
      sb.append(genConstStatic(s"${headerWidgetName}_default_pulse_length", UInt32(cfg.defaultPulseLength)))
    }
    genCRFile()
  }
}
