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

  private val printMods = new mutable.HashSet[ModuleTarget]()

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

    val topWiringPrefix = "synthesizedPrint_"

    def onModule(m: DefModule): DefModule = m match {
      case m: Module if printMods(mTarget(m)) =>
        m.map(onStmt(modToAnnos(mTarget(m)), Namespace(m)))
      case m => m
    }

    def onStmt(annos: Seq[SynthPrintfAnnotation], modNamespace: Namespace)
              (s: Statement): Statement = s.map(onStmt(annos, modNamespace)) match {
      case p @ Print(_,str,args,_,en) if annos.exists(_.format == str.string) =>
        val associatedAnno = annos.find(_.format == str.string).get
        val printName = getPrintName(p, associatedAnno, modNamespace)
        // Generate an aggregate with all of our arguments; this will be wired out
        val wire = DefWire(NoInfo, printName, genPrintBundleType(p))
        val enableConnect = Connect(NoInfo, wsub(WRef(wire), s"enable"), en)
        val argumentConnects = (p.args.zipWithIndex).map({ case (arg, idx) =>
          Connect(NoInfo,
                  wsub(WRef(wire), s"args_${idx}"),
                  arg)})
        topWiringAnnos += TopWiringAnnotation(associatedAnno.mod.ref(printName), topWiringPrefix)
        Block(Seq(p, wire, enableConnect) ++ argumentConnects)
      case s => s
    }

    val processedCircuit = c.map(onModule)
    val wiredState = (new TopWiringTransform).execute(state.copy(
      circuit = processedCircuit,
      annotations = state.annotations ++ topWiringAnnos))


    val topModule = wiredState.circuit.modules.find(_.name == wiredState.circuit.main).get
    val portMap: Map[String, Port] = topModule.ports.map(port => port.name -> port).toMap
    val addedPrintPorts = topLevelOutputs.map({
      case ((_,_,_,path,prefix),_) => portMap(prefix + path.mkString("_"))
    })

    println(s"[MIDAS] total # of prints synthesized: ${addedPrintPorts.size}")

    val printRecordAnno =  addedPrintPorts match {
      case Nil   => Seq()
      case ports => Seq(AddedPrintfIoAnnotation(topWiringPrefix, addedPrintPorts))
    }

    wiredState.copy(annotations = wiredState.annotations ++ printRecordAnno)
  }
}
