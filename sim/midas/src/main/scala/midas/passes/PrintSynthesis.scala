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

import midas.passes.fame.{FAMEChannelConnectionAnnotation, WireChannel}
import midas.widgets.{PrintRecordBag, BridgeIOAnnotation, PrintBridgeModule}
import midas.targetutils.{SynthPrintfAnnotation, GlobalResetConditionSink}

private[passes] class PrintSynthesis extends firrtl.Transform {
  def inputForm = MidForm
  def outputForm = MidForm
  override def name = "[Golden Gate] Print Synthesis"

  private val printMods = new mutable.HashSet[ModuleTarget]()
  private val formatStringMap = new mutable.HashMap[ReferenceTarget, String]()
  val topWiringPrefix = "synthesizedPrintf_"

  // Generates a bundle containing a print's clock, enable, and argument fields
  def genPrintBundleType(print: Print): Type = BundleType(Seq(
    Field("enable", Default, BoolType)) ++
    print.args.zipWithIndex.map({ case (arg, idx) => Field(s"args_${idx}", Default, arg.tpe) }))

  def getPrintName(p: Print, anno: SynthPrintfAnnotation, ns: Namespace): String = {
    // If the user provided a name in the annotation use it; otherwise use the source locator
    val candidateName = anno.name.getOrElse(p.info match {
      case i: FileInfo => i.info.string
      case _ => throw new RuntimeException("Don't know how to generate a name for this printf")
    }).replaceAll("[^A-Za-z0-9_]", "_")
    ns.newName(candidateName)
  }

  // Takes a single printPort and emits an FCCA for each field
  def genFCCAsFromPort(p: Port, portRT: ReferenceTarget, clockRT: ReferenceTarget): Seq[FAMEChannelConnectionAnnotation] = {
    p.tpe match {
      case BundleType(dataFields) =>
        dataFields.map(field =>
          FAMEChannelConnectionAnnotation.source(
            portRT.ref + "_" + field.name,
            WireChannel,
            clock = Some(clockRT),
            Seq(portRT.field(field.name))
          )
        )
      case other => ???
    }
  }

  def synthesizePrints(state: CircuitState, printfAnnos: Seq[SynthPrintfAnnotation]): CircuitState = {
    val c = state.circuit

    def mTarget(m: Module): ModuleTarget = ModuleTarget(c.main, m.name)
    def portRT(p: Port): ReferenceTarget = ModuleTarget(c.main, c.main).ref(p.name)
    def portClockRT(p: Port): ReferenceTarget = portRT(p).field("clock")

    val modToAnnos = printfAnnos.groupBy(_.mod)
    val topWiringAnnos = mutable.ArrayBuffer[Annotation]()

    def onModule(m: DefModule): DefModule = m match {
      case m: Module if printMods(mTarget(m)) =>
        m.map(onStmt(modToAnnos(mTarget(m)), Namespace(m)))
      case m => m
    }

    def onStmt(annos: Seq[SynthPrintfAnnotation], modNamespace: Namespace)
              (s: Statement): Statement = s.map(onStmt(annos, modNamespace)) match {
      case p @ Print(_,format,args,clk ,en) if annos.exists(_.format == format.string) =>
        val associatedAnno = annos.find(_.format == format.string).get
        val printName = getPrintName(p, associatedAnno, modNamespace)
        // Generate an aggregate with all of our arguments; this will be wired out
        val wire = DefWire(NoInfo, printName, genPrintBundleType(p))
        val enableConnect = Connect(NoInfo, WSubField(WRef(wire), "enable"), en)
        val argumentConnects = (p.args.zipWithIndex).map({ case (arg, idx) =>
          Connect(NoInfo,
                  WSubField(WRef(wire), s"args_${idx}"),
                  arg)})

        val printBundleTarget = associatedAnno.mod.ref(printName)
        val clockTarget = clk match {
          case WRef(name,_,_,_) => associatedAnno.mod.ref(name)
          case o => ???
        }
        topWiringAnnos += BridgeTopWiringAnnotation(printBundleTarget, clockTarget)
        formatStringMap(printBundleTarget) = format.serialize
        Block(Seq(p, wire, enableConnect) ++ argumentConnects)
      case s => s
    }
    // Step 1: Find and replace printfs with stubs
    val processedCircuit = c.map(onModule)

    // Step 2: Wire out print stubs to top level module
    val wiredState = (new BridgeTopWiring(topWiringPrefix)).execute(state.copy(
      circuit = processedCircuit,
      annotations = state.annotations ++ topWiringAnnos))

    // Step 3: Group top-wired ports by their associated clock
    val outputAnnos = wiredState.annotations.collect({ case a: BridgeTopWiringOutputAnnotation => a })
    val groupedPrints = outputAnnos.groupBy(_.sinkClockPort)

    println(s"[Golden Gate] total # of printf instances synthesized: ${outputAnnos.size}")

    // Step 4: Generate FCCAs and Bridge Annotations for each clock domain
    val topModule = wiredState.circuit.modules.collectFirst({
      case m@Module(_,name,_,_) if name == c.main => m }).get
    val submodules = wiredState.circuit.modules.filter { _.name != c.main }
    val ns = Namespace(topModule)
    val topMT = ModuleTarget(c.main, c.main)
    val portMap = topModule.ports.map(p => portRT(p) -> p).toMap

    val (addedAnnos, addedPorts, addedStmts) = (for ((sinkClockName, oAnnos) <- groupedPrints.toSeq.sortBy(_._1.ref)) yield {
      val printFCCAs = oAnnos.flatMap({ case BridgeTopWiringOutputAnnotation(_,_,oPortRT,_,oClockRT) =>
        genFCCAsFromPort(portMap(oPortRT), oPortRT, oClockRT) })

      val portTuples = oAnnos.map({ case BridgeTopWiringOutputAnnotation(srcRT,_,oPortRT,_,_) =>
        portMap(oPortRT) -> formatStringMap(srcRT) })

      /**
        * For the global reset condition, add an additional boolean channel. We
        * could wire this to the stubs, but to be consistent across all
        * instrumentation features treat it as a separate chanenl and let the
        * bridge decide how to handle it.
        */
      val resetPortName = ns.newName(s"${sinkClockName.ref}_globalReset")
      val resetPort = Port(NoInfo, resetPortName, Output, BoolType)
      val resetPortRT = topMT.ref(resetPortName)
      val resetPortConn =  Connect(NoInfo, WRef(resetPort), zero)
      val resetFCCA = FAMEChannelConnectionAnnotation.source(
        resetPortName,
        WireChannel,
        clock = printFCCAs.head.clock,
        Seq(resetPortRT))
      val resetConditionAnno = GlobalResetConditionSink(resetPortRT)

      val fccaAnnos = resetFCCA +: printFCCAs
      val bridgeAnno = BridgeIOAnnotation(
        target = ModuleTarget(c.main, c.main).ref(topWiringPrefix.stripSuffix("_")),
        widget = (p: Parameters) => new PrintBridgeModule(resetPortName, portTuples)(p),
        channelNames = fccaAnnos.map(_.globalName)
      )
      (resetConditionAnno +: bridgeAnno +: fccaAnnos, resetPort, resetPortConn)
    }).unzip3
    // Remove added Annotations to prevent being reconsumed by a downstream pass
    val cleanedAnnotations = wiredState.annotations.filterNot(outputAnnos.toSet)
    val updatedTopModule = topModule.copy(
      ports = topModule.ports ++ addedPorts,
      body = Block(topModule.body +: addedStmts))
    wiredState.copy(
      circuit = wiredState.circuit.copy(modules = updatedTopModule +: submodules),
      annotations = cleanedAnnotations ++ addedAnnos.toSeq.flatten)
  }

  def execute(state: CircuitState): CircuitState = {
    val p = state.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p)  => p }).get
    val printfAnnos = state.annotations.collect({
      case anno @ SynthPrintfAnnotation(_, mod, _, _) =>
        printMods += mod
        anno
    })

    val updatedState = if (printfAnnos.length > 0 && p(SynthPrints)) {
      synthesizePrints(state, printfAnnos)
    } else {
      state
    }

    updatedState.copy(annotations = updatedState.annotations.filterNot(printfAnnos.toSet))
  }
}
