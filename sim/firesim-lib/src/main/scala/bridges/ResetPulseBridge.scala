// See LICENSE for license details

package firesim.lib.bridges

import chisel3._

import firesim.lib.bridgeutils._

/** The [[ResetPulseBridge]] drives a bool pulse from time zero for a runtime-configurable number of cycles. These are
  * its elaboration-time parameters.
  *
  * @param activeHigh
  *   When true, reset is initially set at time 0.
  * @param defaultPulseLength
  *   The number of cycles the reset is held at the time 0 value.
  * @param maxPulseLength
  *   The maximum runtime-configurable pulse length that the bridge will support.
  */
case class ResetPulseBridgeParameters(
  activeHigh:         Boolean = true,
  defaultPulseLength: Int     = 50,
  maxPulseLength:     Int     = 1023,
) {
  require(defaultPulseLength <= maxPulseLength)
}

class ResetPulseBridgeTargetIO extends Bundle {
  val clock = Input(Clock())
  val reset = Output(Bool())
}

/** The host-side interface. This bridge has single channel with a bool payload. [[ChannelizedHostPortIO.OutputChannel]]
  * associates the reset on the target side IF with a channel named reset on the host-side BridgeModule (determined by
  * reflection, since we're extending Bundle).
  *
  * @param targetIO
  *   A reference to the bound target-side interface. This need only be a hardware type during target-side
  *   instantiation, so that the direction of the channel can be properly checked, and the correct annotation can be
  *   emitted. During host-side instantiation, the unbound default is used --- this is OK because generateAnnotations()
  *   will not be called.
  *
  * NB: This !MUST! be a private val when extending bundle, otherwise you'll get some extra elements in your host-side
  * IF.
  */
class ResetPulseBridgeHostIO(private val targetIO: ResetPulseBridgeTargetIO = new ResetPulseBridgeTargetIO)
    extends Bundle
    with ChannelizedHostPortIO {
  def targetClockRef = targetIO.clock
  val reset          = OutputChannel(targetIO.reset)
}

class ResetPulseBridge(params: ResetPulseBridgeParameters) extends BlackBox with Bridge[ResetPulseBridgeHostIO] {
  val moduleName     = "midas.widgets.ResetPulseBridgeModule"
  val io             = IO(new ResetPulseBridgeTargetIO)
  val bridgeIO       = new ResetPulseBridgeHostIO(io)
  val constructorArg = Some(params)
  generateAnnotations()
}
