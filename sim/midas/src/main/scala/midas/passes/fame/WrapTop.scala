// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import ir._
import annotations._

// Wrap the top module of a circuit with another module
object WrapTop extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  // TODO: Make these names flexible
  // Previously, it looked like they were flexible in the code, but they weren't
  // This refactors them to reflect that fact
  val topWrapperName = "FAMETop"
  val hostClockName = "hostClock"
  val hostResetName = "hostReset"

  def checkNames(c: Circuit, top: DefModule) = {
    val ns = Namespace(top.ports.map(_.name))
    val portsOK = ns.tryName(hostClockName) && ns.tryName(hostResetName)
    portsOK && ns.tryName(top.name) && Namespace(c).tryName(topWrapperName)
  }

  override def execute(state: CircuitState): CircuitState = {
    val topName = state.circuit.main
    val topModule = state.circuit.modules.find(_.name == topName).get
    assert(checkNames(state.circuit, topModule))

    val topInstance = WDefInstance(topName, topName)
    val portConnections = topModule.ports.map({
      case ip @ Port(_, name, Input, _) => Connect(NoInfo, WSubField(WRef(topInstance), name), WRef(ip))
      case op @ Port(_, name, Output, _) => Connect(NoInfo, WRef(op), WSubField(WRef(topInstance), name))
    })

    val hostClock = Port(NoInfo, hostClockName, Input, ClockType)
    val hostReset = Port(NoInfo, hostResetName, Input, Utils.BoolType)

    val oldCircuitTarget = CircuitTarget(topName)
    val topWrapperTarget = ModuleTarget(topWrapperName, topWrapperName)
    val topWrapper = Module(NoInfo, topWrapperName, hostClock +: hostReset +: topModule.ports, Block(topInstance +: portConnections))
    val specialPortAnnotations = Seq(FAMEHostClock(topWrapperTarget.ref(hostClock.name)), FAMEHostReset(topWrapperTarget.ref(hostReset.name)))

    val renames = RenameMap()
    val newCircuit = Circuit(state.circuit.info, topWrapper +: state.circuit.modules, topWrapperName)
    // Make channel annotations point at top-level ports
    val fccaRenames = RenameMap()
    fccaRenames.record(oldCircuitTarget.module(topName), oldCircuitTarget.module(topWrapperName))

    val updatedAnnotations = state.annotations.map({
      case fcca: FAMEChannelConnectionAnnotation =>
        val renamedInfo = fcca.channelInfo match {
          case fwd: DecoupledForwardChannel => fwd.update(fccaRenames)
          case info => info
        }
        fcca.copy(channelInfo = renamedInfo).update(fccaRenames).head // always returns 1
      case a => a
    })

    renames.record(CircuitTarget(topName), topWrapperTarget.targetParent)
    state.copy(circuit = newCircuit, annotations = updatedAnnotations ++ specialPortAnnotations, renames = Some(renames))
  }
}
