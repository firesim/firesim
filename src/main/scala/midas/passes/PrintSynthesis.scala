package midas
package passes

import java.io.{File, FileWriter, Writer}
import scala.collection.mutable

import firrtl._
import firrtl.annotations.{ReferenceTarget, ModuleTarget, Annotation}
import firrtl.annotations.{ComponentName} // Deprecated
import firrtl.ir._
import firrtl.Mappers._
import firrtl.transforms.TopWiring.{TopWiringAnnotation, TopWiringTransform, TopWiringOutputFilesAnnotation}
import firrtl.Utils.{zero, to_flip, BoolType}
import firrtl.WrappedExpression._

import logger.{Logger, LogLevel}
import freechips.rocketchip.config.{Parameters, Field}

import Utils._
import midas.widgets.{PrintRecordBag}
import midas.targetutils.SynthPrintfAnnotation

case class AddedPrintfIoAnnotation(prefix: String, printPorts: Seq[Port]) extends AddedTargetIoAnnotation[PrintRecordBag]{
  def generateChiselIO(): (String, PrintRecordBag) = (prefix.stripSuffix("_"), new PrintRecordBag(prefix, printPorts))
  def update(renames: RenameMap): Seq[AddedPrintfIoAnnotation] = Seq(this)
}

private[passes] class PrintSynthesis(dir: File)(implicit p: Parameters) extends firrtl.Transform {
  def inputForm = MidForm
  def outputForm = MidForm
  override def name = "[MIDAS] Print Synthesis"

  type Messages = mutable.HashMap[Int, String]
  type Prints =  mutable.ArrayBuffer[Print]
  type Formats = mutable.ArrayBuffer[(String, Seq[String])]

  private val messages = mutable.HashMap[String, Messages]()
  private val prints = mutable.HashMap[String, Prints]()
  private val printPorts = mutable.HashMap[String, Port]()
  private val printMods = new mutable.HashSet[ModuleTarget]()

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

  // Generates a bundle to aggregate
  def genPrintBundleType(print: Print): Type = BundleType(Seq(
    Field("enable", Default, BoolType)) ++
    print.args.zipWithIndex.map({ case (arg, idx) => Field(s"args_${idx}", Default, arg.tpe) })
  )

  def getPrintName(p: Print, anno: SynthPrintfAnnotation, ns: Namespace): String = {
    // If the user provided a name in the annotation use it; otherwise use the source locator
    val candidateName = anno.name.getOrElse(p.info match {
      case i: FileInfo => i.info.string map { _ match {
        case ' ' | '.' | ':' => '_'
        case c => c
      }}
      case _ => throw new RuntimeException("Don't know how to generate a name for this printf")
    })
    ns.newName(candidateName)
  }

  // Hacky: Instead of generated output files, instead sneak out the mappings from the TopWiring
  // transform.
  type TopWiringSink = ((ComponentName, Type, Boolean, Seq[String], String), Int)
  var topLevelOutputs = Seq[TopWiringSink]()
  def wiringAnnoOutputFunc(td: String,
                           mappings:  Seq[TopWiringSink],
                           state: CircuitState): CircuitState = {
    topLevelOutputs = mappings
    state
  }


  def execute(state: CircuitState): CircuitState = {
    val c = state.circuit

    def mTarget(m: Module): ModuleTarget = ModuleTarget(c.main, m.name)

    val printfAnnos = state.annotations.collect({
      case a @ SynthPrintfAnnotation(_, mod, _, _) =>
        printMods += mod; a
    })
    val modToAnnos = printfAnnos.groupBy(_.mod)

    val topWiringAnnos = mutable.ArrayBuffer[Annotation](
      TopWiringOutputFilesAnnotation("unused", wiringAnnoOutputFunc))


    def onModule(m: DefModule): DefModule = m match {
      case m: Module if printMods(mTarget(m)) =>
        m.map(onStmt(modToAnnos(mTarget(m)), Namespace(m)))
      case m => m
    }

    def onStmt(annos: Seq[SynthPrintfAnnotation], modNamespace: Namespace)
              (s: Statement): Statement = s.map(onStmt(annos, modNamespace)) match {
      case p @ Print(_,str,args,_,en) if annos.exists(_.format == str.string) =>
        printNum += 1
        val associatedAnno = annos.find(_.format == str.string).get
        val printName = getPrintName(p, associatedAnno, modNamespace)
        // Generate an aggregate with all of our arguments; this will be wired out
        val wire = DefWire(NoInfo, printName, genPrintBundleType(p))
        val enableConnect = Connect(NoInfo, wsub(WRef(wire), s"enable"), en)
        val argumentConnects = (p.args.zipWithIndex).map({ case (arg, idx) =>
          Connect(NoInfo,
                  wsub(WRef(wire), s"args_${idx}"),
                  arg)})
        topWiringAnnos += TopWiringAnnotation(associatedAnno.mod.ref(printName), s"synthesizedPrint_")
        Block(Seq(wire, enableConnect) ++ argumentConnects)
      case Print(_,_,_,_,_) => EmptyStmt
      case s => s
    }

    Logger.setLevel(LogLevel.Trace)
    val processedCircuit = c.map(onModule)
    val wiredState = (new TopWiringTransform).execute(state.copy(
      circuit = processedCircuit,
      annotations = state.annotations ++ topWiringAnnos))

    println(s"[MIDAS] total # of prints synthesized: $printNum")

    val printAnnos = if (printNum > 0) {
      topLevelOutputs foreach println
      Seq()
    } else {
      Seq()
    }
    Logger.setLevel(LogLevel.None)

    wiredState
  }
}
