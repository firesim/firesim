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
import midas.passes.fame.{FAMEChannelConnectionAnnotation, WireChannel}
import midas.widgets.{PrintRecordBag, BridgeIOAnnotation, PrintBridgeModule}
import midas.targetutils.SynthPrintfAnnotation

private[passes] class PrintSynthesis(dir: File)(implicit p: Parameters) extends firrtl.Transform {
  def inputForm = MidForm
  def outputForm = MidForm
  override def name = "[MIDAS] Print Synthesis"

  private val printMods = new mutable.HashSet[ModuleTarget]()
  private val formatStringMap = new mutable.HashMap[ReferenceTarget, String]()

  // Generates a bundle to aggregate
  def genPrintBundleType(print: Print): Type = BundleType(Seq(
    Field("enable", Default, BoolType)) ++
    print.args.zipWithIndex.map({ case (arg, idx) => Field(s"args_${idx}", Default, arg.tpe) })
  )

  def getPrintName(p: Print, anno: SynthPrintfAnnotation, ns: Namespace): String = {
    // If the user provided a name in the annotation use it; otherwise use the source locator
    val candidateName = anno.name.getOrElse(p.info match {
      case i: FileInfo => i.info.string
      case _ => throw new RuntimeException("Don't know how to generate a name for this printf")
    }).replaceAll("[^A-Za-z0-9_]", "_")
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

  // Takes a single printPort and emits an FCCA for each field
  def genFCCAsFromPort(mT: ModuleTarget, p: Port): Seq[FAMEChannelConnectionAnnotation] = {
    println(p)
    p.tpe match {
      case BundleType(fields) =>
        fields.map(field =>
          FAMEChannelConnectionAnnotation(
            p.name + "_" + field.name,
            WireChannel,
            sources = Some(Seq(mT.ref(p.name).field(field.name))),
            sinks = None
          )
        )
      case other => Seq()
    }
  }

  def synthesizePrints(state: CircuitState, printfAnnos: Seq[SynthPrintfAnnotation]): CircuitState = {
    require(state.annotations.collect({ case t: TopWiringAnnotation => t }).isEmpty,
      "CircuitState cannot have existing TopWiring annotations before PrintSynthesis.")
    val c = state.circuit
    def mTarget(m: Module): ModuleTarget = ModuleTarget(c.main, m.name)

    val modToAnnos = printfAnnos.groupBy(_.mod)

    val topWiringAnnos = mutable.ArrayBuffer[Annotation](
      TopWiringOutputFilesAnnotation("unused", wiringAnnoOutputFunc))

    val topWiringPrefix = ""

    def onModule(m: DefModule): DefModule = m match {
      case m: Module if printMods(mTarget(m)) =>
        m.map(onStmt(modToAnnos(mTarget(m)), Namespace(m)))
      case m => m
    }

    def onStmt(annos: Seq[SynthPrintfAnnotation], modNamespace: Namespace)
              (s: Statement): Statement = s.map(onStmt(annos, modNamespace)) match {
      case p @ Print(_,format,args,_,en) if annos.exists(_.format == format.string) =>
        val associatedAnno = annos.find(_.format == format.string).get
        val printName = getPrintName(p, associatedAnno, modNamespace)
        // Generate an aggregate with all of our arguments; this will be wired out
        val wire = DefWire(NoInfo, printName, genPrintBundleType(p))
        val enableConnect = Connect(NoInfo, wsub(WRef(wire), s"enable"), en)
        val argumentConnects = (p.args.zipWithIndex).map({ case (arg, idx) =>
          Connect(NoInfo,
                  wsub(WRef(wire), s"args_${idx}"),
                  arg)})

        val printBundleTarget = associatedAnno.mod.ref(printName)
        topWiringAnnos += TopWiringAnnotation(printBundleTarget, topWiringPrefix)
        formatStringMap(printBundleTarget) = format.serialize
        Block(Seq(p, wire, enableConnect) ++ argumentConnects)
      case s => s
    }

    val processedCircuit = c.map(onModule)
    val wiredState = (new TopWiringTransform).execute(state.copy(
      circuit = processedCircuit,
      annotations = state.annotations ++ topWiringAnnos))

    val topModule = wiredState.circuit.modules.find(_.name == wiredState.circuit.main).get
    val portMap: Map[String, Port] = topModule.ports.map(port => port.name -> port).toMap
    val addedPrintPorts = topLevelOutputs.map({ case ((cname,_,_,path,prefix),_) =>
      // Look up the format string by regenerating the referenceTarget to the original print bundle
      val formatString = formatStringMap(cname)
      val port = portMap(prefix + path.mkString("_"))
      (port, formatString)
    })

    println(s"[MIDAS] total # of prints synthesized: ${addedPrintPorts.size}")

    val printRecordAnno =  addedPrintPorts match {
      case Nil   => Seq()
      case ports => {
        // TODO: Generate sensible channel annotations once we can aggregate wire channels
        val portName = topWiringPrefix.stripSuffix("_")
        val mT = ModuleTarget(c.main, c.main)
        val portRT = mT.ref(portName)

        val fccaAnnos = ports.flatMap({ case (port, _) => genFCCAsFromPort(mT, port) })
        val bridgeAnno = BridgeIOAnnotation(
          target = portRT,
          widget = (p: Parameters) => new PrintBridgeModule(topWiringPrefix, addedPrintPorts)(p),
          channelNames = fccaAnnos.map(_.globalName)
        )
        bridgeAnno +: fccaAnnos
      }
    }
    // Remove added TopWiringAnnotations to prevent being reconsumed by a downstream pass
    val cleanedAnnotations = wiredState.annotations.flatMap({
      case TopWiringAnnotation(_,_) => None
      case otherAnno => Some(otherAnno)
    })
    wiredState.copy(annotations = cleanedAnnotations ++ printRecordAnno)
  }

  def execute(state: CircuitState): CircuitState = {
    val printfAnnos = state.annotations.collect({
      case anno @ SynthPrintfAnnotation(_, mod, _, _) =>
        printMods += mod
        anno
    })

    if (printfAnnos.length > 0 && p(SynthPrints)) {
      synthesizePrints(state, printfAnnos)
    } else {
      state
    }
  }
}
