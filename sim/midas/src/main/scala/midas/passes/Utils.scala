// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.ir._
import java.io.File

object Utils {
  def cat(es: Seq[Expression]): Expression =
    if (es.tail.isEmpty) es.head else {
      val left = cat(es.slice(0, es.length/2))
      val right = cat(es.slice(es.length/2, es.length))
      DoPrim(PrimOps.Cat, Seq(left, right), Nil, UnknownType)
    }

  // Takes a circuit state and writes it out to the target-directory by selecting
  // an appropriate emitter for its form
  def writeState(state: CircuitState, name: String): Unit = {
    val td = state.annotations.collectFirst({ case TargetDirAnnotation(value) => value })
    val file = td match {
      case Some(dir) => new File(dir, name)
      case None      => new File(name)
    }
    val writer = new java.io.FileWriter(file)
    val emitter = state.form match {
      case LowForm  => new LowFirrtlEmitter
      case MidForm  => new MiddleFirrtlEmitter
      case HighForm => new HighFirrtlEmitter
      case        _ => throw new RuntimeException("Cannot select emitter for unrecognized form.")
    }
    emitter.emit(state, writer)
    writer.close
  }
}

// Writes out the circuit to a file for debugging
class EmitFirrtl(fileName: String) extends firrtl.Transform {

  def inputForm = HighForm
  def outputForm = HighForm
  override def name = s"[Golden Gate] Debugging Emission Pass: $fileName"

  def execute(state: CircuitState) = {
    Utils.writeState(state, fileName)
    state
  }
}

