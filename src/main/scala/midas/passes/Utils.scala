// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.{sub_type, field_type}
import mdf.macrolib._
import mdf.macrolib.Utils.writeMDFToString
import scala.collection.mutable.{ArrayBuffer, HashSet, LinkedHashSet}
import java.io.{File, FileWriter, Writer}

object Utils {
  val ut = UnknownType
  val uw = UnknownWidth
  val ug = UNKNOWNGENDER
  
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

  def inv(p: PortPolarity): Boolean = p match {
    case ActiveHigh | PositiveEdge => false
    case ActiveLow  | NegativeEdge => true
  }
  def inv(e: Expression, p: PortPolarity): Expression =
    if (inv(p)) not(e) else e

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

case class MemConf(
  name: String,
  depth: BigInt,
  width: BigInt,
  readers: Seq[String],
  writers: Seq[String],
  readwriters: Seq[String],
  maskGran: BigInt) {
  def toSRAMMacro: SRAMMacro = {
    val readPorts = readers.zipWithIndex map { case (r, i) => MacroPort(
      PolarizedPort(s"R${i}_addr", ActiveHigh),
      Some(PolarizedPort(s"R${i}_clk", ActiveHigh)),
      output = Some(PolarizedPort(s"R${i}_data", ActiveHigh)),
      chipEnable = Some(PolarizedPort(s"R${i}_en", ActiveHigh)),
      depth  = Some(depth.toInt),
      width  = Some(width.toInt)
    )}
    val writePorts = writers.zipWithIndex map { case (w, i) => MacroPort(
      PolarizedPort(s"W${i}_addr", ActiveHigh),
      Some(PolarizedPort(s"W${i}_clk", ActiveHigh)),
      input = Some(PolarizedPort(s"W${i}_data", ActiveHigh)),
      chipEnable = Some(PolarizedPort(s"W${i}_en", ActiveHigh)),
      maskPort = if (w.head == 'm') Some(PolarizedPort(s"W${i}_mask", ActiveHigh)) else None,
      maskGran = if (w.head == 'm') Some(maskGran.toInt) else None,
      depth  = Some(depth.toInt),
      width  = Some(width.toInt)
    )}
    val readwritePorts = readwriters.zipWithIndex map { case (rw, i) => MacroPort(
      PolarizedPort(s"RW${i}_addr", ActiveHigh),
      Some(PolarizedPort(s"RW${i}_clk", ActiveHigh)),
      input = Some(PolarizedPort(s"RW${i}_wdata", ActiveHigh)),
      output = Some(PolarizedPort(s"RW${i}_rdata", ActiveHigh)),
      chipEnable = Some(PolarizedPort(s"RW${i}_en", ActiveHigh)),
      writeEnable = Some(PolarizedPort(s"RW${i}_wmode", ActiveHigh)),
      maskPort = if (rw.head == 'm') Some(PolarizedPort(s"RW${i}_wmask", ActiveHigh)) else None,
      maskGran = if (rw.head == 'm') Some(maskGran.toInt) else None,
      depth  = Some(depth.toInt),
      width  = Some(width.toInt)
    )}
    SRAMMacro(name, width.toInt, depth.toInt, "",
              readPorts ++ writePorts ++ readwritePorts)
  }
}

object MemConfReader {
  sealed trait ConfField
  case object Name extends ConfField
  case object Depth extends ConfField
  case object Width extends ConfField
  case object Ports extends ConfField
  case object MaskGran extends ConfField
  type ConfFieldMap = Map[ConfField, String]
  // Read a conf file generated by [[firrtl.passes.ReplSeqMems]] 
  def apply(conf: java.io.File): Seq[MemConf] = {
    def parse(map: ConfFieldMap, list: List[String]): ConfFieldMap = list match {
      case Nil => map
      case "name" :: value :: tail => parse(map + (Name -> value), tail)
      case "depth" :: value :: tail => parse(map + (Depth -> value), tail)
      case "width" :: value :: tail => parse(map + (Width -> value), tail)
      case "ports" :: value :: tail => parse(map + (Ports -> value), tail)
      case "mask_gran" :: value :: tail => parse(map + (MaskGran -> value), tail)
      case field :: tail => firrtl.Utils.error(s"Unknown field $field")
    }
    io.Source.fromFile(conf).getLines.toSeq map { line =>
      val map = parse(Map[ConfField, String](), (line split " ").toList)
      val ports = map(Ports) split ","
      MemConf(map(Name), BigInt(map(Depth)), BigInt(map(Width)),
        ports filter (_ == "read"),
        ports filter (p => p == "write" || p == "mwrite"),
        ports filter (p => p == "rw" || p == "mrw"),
        map get MaskGran map (BigInt(_)) getOrElse (BigInt(map(Width))))
    }
  }
}

class ConfToJSON(conf: File, json: File) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  def execute(state: CircuitState) = {
    val macros = MemConfReader(conf) map (_.toSRAMMacro)
    val writer = new FileWriter(json)
    writer write writeMDFToString(macros)
    writer.close
    state
  }
}
