// See LICENSE for license details.

package midas.widgets

import org.chipsalliance.cde.config.{Field, Parameters}

import chisel3._

import firesim.lib.bridgeutils.{HasChannels, RationalClock}

/* Bridge
 *
 * Bridges are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 */

// Set in FPGA Top before the BridgeModule is generated
case object TargetClockInfo extends Field[Option[RationalClock]]

abstract class BridgeModule[HostPortType <: Record with HasChannels]()(implicit p: Parameters) extends Widget()(p) {
  def module: BridgeModuleImp[HostPortType]
}

abstract class BridgeModuleImp[HostPortType <: Record with HasChannels](
  wrapper:    BridgeModule[_ <: HostPortType]
)(implicit p: Parameters
) extends WidgetImp(wrapper) {
  def hPort: HostPortType
  def clockDomainInfo: RationalClock = p(TargetClockInfo).get
}
