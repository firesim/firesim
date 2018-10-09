// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.annotations.{CircuitName, ModuleName}
import firrtl.ir._
import firrtl.Mappers._
import firrtl.transforms.fame.FAMETransformAnnotation
import Utils._
import java.io.{File, FileWriter, StringWriter}

private[passes] class ChannelizeTargetIO(io: Seq[chisel3.Data]) extends firrtl.Transform {

  override def name = "[MIDAS] ChannelizeTargetIO"
  def inputForm = LowForm
  def outputForm = LowForm

  def execute(state: CircuitState): CircuitState = {
    val circuit = state.circuit

    val moduleMap = circuit.modules.map(m => m.name -> m).toMap
    val topModule = moduleMap(circuit.main)

    val portMap = topModule.ports.flatMap({
      case Port(_,_,_,ClockType) => None
      case Port(_,name,Output,_) => Some(name -> name)
      case Port(_,name,Input,_)  => Some(name -> name)
    }).toMap

    val cname = CircuitName(circuit.main)
    val f1Anno = FAMETransformAnnotation(ModuleName(circuit.main, cname), portMap)

    state.copy(annotations = state.annotations ++ Seq(f1Anno))
  }
}
