package midas
package passes

import java.io.{File, FileWriter, Writer}

import firrtl._
import firrtl.annotations.{ReferenceTarget, ModuleTarget}
import firrtl.ir._
import firrtl.Mappers._
import firrtl.WrappedExpression._
import firrtl.Utils.{zero, to_flip}

import freechips.rocketchip.config.{Parameters, Field}

import Utils._
import midas.widgets.{PrintRecord, AssertBundle}
import midas.targetutils.SynthPrintfAnnotation

import DebugPassUtils._

private[passes] class PrintSynthesis(dir: File)(implicit p: Parameters) extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[MIDAS] Print Synthesis"

  type Messages = collection.mutable.HashMap[Int, String]
  type Prints = collection.mutable.ArrayBuffer[Print]
  type Formats = collection.mutable.ArrayBuffer[(String, Seq[String])]

  private val messages = collection.mutable.HashMap[String, Messages]()
  private val prints = collection.mutable.HashMap[String, Prints]()
  private val printPorts = collection.mutable.HashMap[String, Port]()
  private val printMods = new collection.mutable.LinkedHashMap[ModuleTarget, SynthPrintfAnnotation]

  // Helper method to filter out unwanted prints
  private def excludePrint(printMessage: String): Boolean =
    p(PrintExcludes).exists(ex => printMessage contains(ex))

  // Helper method to filter out module instances
  private def excludeInst(excludes: Seq[(String, String)])
                         (parentMod: String, inst: String): Boolean =
    excludes.exists({case (exMod, exInst) => parentMod == exMod && inst == exInst })

  private def excludeInstPrints = excludeInst(p(ExcludeInstancePrints)) _


  private def findMessages(mname: String)(s: Statement): Statement = s map findMessages(mname) match {
    case s: Print if p(SynthPrints) && s.args.nonEmpty && !excludePrint(mname) =>
      prints(mname) += s
      EmptyStmt
    case s => s
  }

//  private def transform(m: DefModule): DefModule = {
//    val namespace = Namespace(m)
//    messages(m.name) = new Messages
//    prints(m.name) = new Prints
//
//    (m map findMessages(m.name)) match {
//      case m: Module if m=>
//        val ports = collection.mutable.ArrayBuffer[Port]()
//        val stmts = collection.mutable.ArrayBuffer[Statement]()
//        // Connect prints
//        val printChildren = getChildren(printPorts, p(ExcludeInstancePrints))
//        if (printChildren.size + prints(m.name).size > 0) {
//          val tpe = BundleType((prints(m.name).zipWithIndex map { case (print, idx) =>
//              val total = (print.args foldLeft 0)((res, arg) => res + bitWidth(arg.tpe).toInt)
//              val width = 8 * ((total - 1) / 8 + 1)
//              Field(s"print_${idx}", Default, UIntType(IntWidth(width + 1)))
//            }) ++ (printChildren flatMap { case (child, p) => p.tpe match {
//              // Field(child, Default, p.tpe)
//              case BundleType(fs) => fs map (f => f.copy(name=s"${child}_${f.name}")) }
//            })
//          )
//          val port = Port(NoInfo, namespace.newName("midasPrints"), Output, tpe)
//          printPorts(m.name) = port
//          ports += port
//          stmts ++= (printChildren flatMap { case (child, p) => p.tpe match {
//            case BundleType(fs) => fs map (f =>
//              Connect(NoInfo, wsub(WRef(port.name), s"${child}_${f.name}"),
//                              wsub(wsub(WRef(child), p.name), f.name)))
//          }}) ++ (prints(m.name).zipWithIndex map { case (print, idx) =>
//              Connect(NoInfo, wsub(WRef(port.name), s"print_${idx}"),
//                              cat(print.args.reverse :+ print.en))
//          })
//        }
//        m.copy(ports = m.ports ++ ports.toSeq, body = Block(m.body +: stmts.toSeq))
//      case m: ExtModule => m
//    }
//  }

  private var printNum = 0
//  def dump(writer: Writer, meta: StroberMetaData, mod: String, path: String): Unit = { 
//    prints(mod) foreach { print =>
//      writer write """%s""".format(print.string.serialize)
//      writer write s"\n"
//      writer write (print.args map (arg => s"${path}.${arg.serialize} ${bitWidth(arg.tpe)}") mkString " ")
//      writer write s"\n"
//      printNum += 1
//    }
//    meta.childInsts(mod).reverse
//        .filterNot(inst => excludeInstPrints(mod, inst))
//        .foreach(child => dump(writer, meta, meta.instModMap(child, mod), s"${path}.${child}"))
//  }

  def execute(state: CircuitState): CircuitState = {
    val c = state.circuit

    def mTarget(m: Module): ModuleTarget = ModuleTarget(c.main, m.name)

    val printfAnnos = state.annotations.collect({
      case a @ SynthPrintfAnnotation(_, mod, _) =>
        println(a)
        printMods(mod) = a; a
    })

    def onModule(m: DefModule): DefModule = m match {
      case m: Module if printMods.contains(mTarget(m)) => m
      case m => m
    }

    val mods = c.map(onModule)
    //val f = new FileWriter(new File(dir, s"${c.main}.prints"))
    //dump(f, meta, c.main, c.main)
    //f.close

    println(s"[MIDAS] total # of prints synthesized: $printNum")

    val printAnnos = if (printNum > 0) {
      Seq(AddedTargetIoAnnotation(printPorts(c.main), PrintRecord.apply))
    } else {
      Seq()
    }

    state
  }
}
