// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import ir._
import annotations._

// Wrap the top module of a circuit with another module
class WrapTop extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  override def execute(state: CircuitState): CircuitState = {
    val topName = state.circuit.main
    val topModule = state.circuit.modules.find(_.name == topName).get
    val circuitNS = Namespace(state.circuit)
    val topWrapperName = circuitNS.newName("FAMETop")
    val topWrapperNS = Namespace(topModule.ports.map(_.name))
    val topInstance = WDefInstance(topWrapperNS.newName(topName), topName)
    val portConnections = topModule.ports.map({
      case ip @ Port(_, name, Input, _) => Connect(NoInfo, WSubField(WRef(topInstance), name), WRef(ip))
      case op @ Port(_, name, Output, _) => Connect(NoInfo, WRef(op), WSubField(WRef(topInstance), name))
    })
    val clocks = topModule.ports.filter(_.tpe == ClockType)
    val hostClock = clocks.find(_.name == "clock").getOrElse(clocks.head)
    val hostReset = HostReset.makePort(topWrapperNS)
    val oldCircuitTarget = CircuitTarget(topName)
    val topWrapperTarget = ModuleTarget(topWrapperName, topWrapperName)
    val topWrapper = Module(NoInfo, topWrapperName, topModule.ports :+ hostReset, Block(topInstance +: portConnections))
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
