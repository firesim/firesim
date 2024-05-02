// See LICENSE for license details.

package midas.passes


import firrtl._
import firrtl.ir._


// Ensures that there are no dangling IO on the target. All I/O coming off the DUT must be bound
// to an Bridge BlackBox
case class TargetMalformedException(message: String) extends RuntimeException(message)

private[passes] object EnsureNoTargetIO extends firrtl.Transform {
  def inputForm = HighForm
  def outputForm = HighForm
  override def name = "[MIDAS] Ensure No Target IO"

  def execute(state: CircuitState): CircuitState = {
    val topName = state.circuit.main
    val topModule = state.circuit.modules.find(_.name == topName).get

    val (clockPorts, nonClockPorts) = topModule.ports.partition(_.tpe ==  ClockType)

    if (!clockPorts.isEmpty) {
      val exceptionMessage = "Your target design has the following unexpected clock ports:\n" +
        clockPorts.map(_.name).mkString("\n") +
        "\nRemove these ports and generate clocks for your simulated system using a ClockBridge."
      throw TargetMalformedException(exceptionMessage)
    }

    if (!nonClockPorts.isEmpty) {
      val exceptionMessage = "Your target design has the following unexpecte IO ports:\n" +
        nonClockPorts.map(_.name).mkString("\n") +
        "\nRemove these ports and instead bind their sources/sinks to a target-to-host Bridge."
      throw TargetMalformedException(exceptionMessage)
    }
    state
  }
}
