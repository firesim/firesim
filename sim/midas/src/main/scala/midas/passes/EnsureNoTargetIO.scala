// See LICENSE for license details.

package midas.passes

import midas.widgets.BridgeAnnotation
import midas.passes.fame.{PromoteSubmodule, PromoteSubmoduleAnnotation, FAMEChannelConnectionAnnotation}

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.transforms.TopWiring.{TopWiringAnnotation, TopWiringTransform, TopWiringOutputFilesAnnotation}
import firrtl.passes.wiring.{Wiring, WiringInfo}
import Utils._

import scala.collection.mutable
import java.io.{File, FileWriter, StringWriter}

// Ensures that there are no dangling IO on the target. All I/O coming off the DUT must be bound
// to an Bridge BlackBox
private[passes] class EnsureNoTargetIO extends firrtl.Transform {
  def inputForm = HighForm
  def outputForm = HighForm
  override def name = "[MIDAS] Ensure No Target IO"

  def execute(state: CircuitState): CircuitState = {
    val topName = state.circuit.main
    val topModule = state.circuit.modules.find(_.name == topName).get

    val nonClockPorts = topModule.ports.filter(_.tpe !=  ClockType)

    if (!nonClockPorts.isEmpty) {
      val exceptionMessage = """
Your target design has dangling IO.
You must bind the following top-level ports to an Bridge BlackBox:
""" + nonClockPorts.map(_.name).mkString("\n")
      throw new Exception(exceptionMessage)
    }
    state
  }
}
