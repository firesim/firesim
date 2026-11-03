// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.ir._
import java.io.File

object Utils {
  def cat(es: Seq[Expression]): Expression =
    if (es.tail.isEmpty) es.head
    else {
      val left  = cat(es.slice(0, es.length / 2))
      val right = cat(es.slice(es.length / 2, es.length))
      DoPrim(PrimOps.Cat, Seq(left, right), Nil, UnknownType)
    }

  def orderedCat(es: Seq[Expression]): Expression =
    if (es.tail.isEmpty) es.head
    else {
      DoPrim(PrimOps.Cat, Seq(orderedCat(es.tail), es.head), Nil, UnknownType)
    }

  // Takes a circuit state and writes it out to the target-directory by selecting
  // an appropriate emitter for its form
  def writeState(state: CircuitState, name: String): Unit = {
    val td      = state.annotations.collectFirst({ case TargetDirAnnotation(value) => value })
    val file    = td match {
      case Some(dir) => new File(dir, name)
      case None      => new File(name)
    }
    // Stream the circuit to the file. The form-specific emitters build the whole circuit as one
    // String (Circuit.serialize), which for very large designs (the 4-SM Radiance SoC is ~2 GB of
    // FIRRTL) exceeds the JVM's 2 GiB array limit: "Required array length 2147483639 + N is too large".
    val writer = new java.io.BufferedWriter(new java.io.FileWriter(file), 1 << 20)
    try {
      firrtl.ir.Serializer.lazily(state.circuit).foreach(writer.write)
      writer.write("\n")
    } finally {
      writer.close()
    }
  }
}

// Writes out the circuit to a file for debugging
class EmitFirrtl(fileName: String) extends firrtl.Transform {

  def inputForm     = HighForm
  def outputForm    = HighForm
  override def name = s"[Golden Gate] Debugging Emission Pass: $fileName"

  def execute(state: CircuitState) = {
    Utils.writeState(state, fileName)
    state
  }
}
