// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.annotations.TargetToken.{OfModule, Instance, Field}
import firrtl.transforms.{CheckCombLoops, LogicNode}

/**
  * Contains exception classes for [[midas.passes.ClockSourceFinder]]
  */
object ClockSourceFinder {
  class MultipleDriversException(target: ReferenceTarget, module: String, drivers: Seq[String])
      extends Exception(s"Clock ${target} is driven by multiple signals in module ${module}: ${drivers}", null)
}

/** A utility for finding the upstream drivers of arbitrary clock signals in a circuit.
  * find and return that input port.
  *
  * @param state the CircuitState to analyze
  */
class ClockSourceFinder(state: CircuitState) {
  import ClockSourceFinder._

  private def inputClockNodes(m: DefModule) = m.ports.collect {
    case Port(_, name, Input, ClockType) => LogicNode(name)
  }

  private val moduleMap = state.circuit.modules.map(m => m.name -> m).toMap
  private val inputClockNodeSets = state.circuit.modules.map(m => m.name -> inputClockNodes(m).toSet).toMap
  private lazy val connectivity = new CheckCombLoops().analyzeFull(state)

  /** If a clock signal is directly driven by an input clock port at the top of a multi-module hierarchy,
    * find and return that input port.
    *
    * @param queryTarget the downstream clock to analyze
    * @return an option containing a "local" reference to the port in queryTarget.module that drives queryTarget, if any.
    * @note The analysis is limited to the hierarchy rooted at the root module of queryTarget
    */
  def findRootDriver(queryTarget: ReferenceTarget): Option[ReferenceTarget] = {
    require(queryTarget.component.isEmpty)
    def getPortDriver(rT: ReferenceTarget): Option[ReferenceTarget] = {
      val portOption = rT.component.collectFirst { case Field(f) => f }
      val node = portOption.map(p => LogicNode(p, Some(rT.ref))).getOrElse(LogicNode(rT.ref))
      val (inst, module) = rT.path.lastOption.getOrElse((Instance(rT.module), OfModule(rT.module)))
      val drivingCone = connectivity(module.value).reachableFrom(node)
      val drivingPorts = (drivingCone + node) & inputClockNodeSets(module.value)
      if (drivingPorts.size == 0) {
        None
      } else if (drivingPorts.size > 1) {
        throw new MultipleDriversException(queryTarget, module.value, drivingPorts.map(_.name).toSeq)
      } else {
        val drivingPort = drivingPorts.head.name
        if (rT.path.isEmpty) {
          Some(rT.copy(ref = drivingPort, component = Nil))
        } else {
          val parentRT = rT.copy(path = rT.path.init, ref = inst.value, component = Seq(Field(drivingPort)))
          getPortDriver(parentRT)
        }
      }
    }
    getPortDriver(queryTarget)
  }
}
