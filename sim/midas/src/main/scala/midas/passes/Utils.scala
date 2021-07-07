// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.Utils.{sub_type, field_type}
import scala.collection.mutable.{ArrayBuffer, HashSet, LinkedHashSet, LinkedHashMap}
import java.io.{File, FileWriter, Writer}

object Utils {
  val ut = UnknownType
  val uw = UnknownWidth
  val ug = UnknownFlow
  
  def wref(s: String, t: Type = ut, k: Kind = ExpKind) = WRef(s, t, k, ug)
  def wsub(e: Expression, s: String) = WSubField(e, s, field_type(e.tpe, s), ug)
  def widx(e: Expression, i: Int) = WSubIndex(e, i, sub_type(e.tpe), ug)
  def not(e: Expression) = DoPrim(PrimOps.Not, Seq(e), Nil, e.tpe)
  private def getType(e1: Expression, e2: Expression) = e2.tpe match {
    case UnknownType => e1.tpe
    case _ => e2.tpe
  }
  def or(e1: Expression, e2: Expression) =
    DoPrim(PrimOps.Or, Seq(e1, e2), Nil, getType(e1, e2))
  def and(e1: Expression, e2: Expression) =
    DoPrim(PrimOps.And, Seq(e1, e2), Nil, getType(e1, e2))
  def bits(e: Expression, high: BigInt, low: BigInt) =
    DoPrim(PrimOps.Bits, Seq(e), Seq(high, low), e.tpe)
  def cat(es: Seq[Expression]): Expression =
    if (es.tail.isEmpty) es.head else {
      val left = cat(es.slice(0, es.length/2))
      val right = cat(es.slice(es.length/2, es.length))
      DoPrim(PrimOps.Cat, Seq(left, right), Nil, ut)
    }

  def renameMods(c: Circuit, namespace: Namespace) = {
    val (modules, nameMap) = (c.modules foldLeft (Seq[DefModule](), Map[String, String]())){
      case ((ms, map), m: ExtModule) =>
        val newMod = (map get m.name) match {
          case None => m copy (name = namespace newName m.name)
          case Some(name) => m copy (name = name)
        }
        ((ms :+ newMod), map + (m.name -> newMod.name))
      case ((ms, map), m: Module) =>
        val newMod = (map get m.name) match {
          case None => m copy (name = namespace newName m.name)
          case Some(name) => m copy (name = name)
        }
        ((ms :+ newMod), map + (m.name -> newMod.name))
    }
    def updateModNames(s: Statement): Statement = s match {
      case s: WDefInstance => s copy (module = nameMap(s.module))
      case s => s map updateModNames
    }
    c copy (modules = modules map (_ map updateModNames), main = nameMap(c.main))
  }

  // Takes a circuit state and writes it out to the target-directory by selecting
  // an appropriate emitter for its form
  def writeState(state: CircuitState, name: String) {
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

  // Takes a circuitState that has been emitted and writes the result to file
  def writeEmittedCircuit(state: CircuitState, file: File) {
    val f = new FileWriter(file)
    f.write(state.getEmittedCircuit.value)
    f.close
  }

}

// Lowers a circuitState from form A to form B, but unlike the lowering compilers
// provided by firrtl, doesn't assume a chirrtl input form
class IntermediateLoweringCompiler(inputForm: CircuitForm, outputForm: CircuitForm) extends firrtl.Compiler {
  def emitter = outputForm match {
    case LowForm  => new LowFirrtlEmitter
    case MidForm  => new MiddleFirrtlEmitter
    case HighForm => new HighFirrtlEmitter
  }
  def transforms = firrtl.CompilerUtils.getLoweringTransforms(inputForm, outputForm)
}

// Writes out the circuit to a file for debugging
class EmitFirrtl(fileName: String) extends firrtl.Transform {

  def inputForm = HighForm
  def outputForm = HighForm
  override def name = s"[MIDAS] Debugging Emission Pass: $fileName"

  def execute(state: CircuitState) = {
    Utils.writeState(state, fileName)
    state
  }
}

