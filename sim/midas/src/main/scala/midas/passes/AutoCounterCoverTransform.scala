// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.passes._
import firrtl.passes.wiring._
import firrtl.Utils.{throwInternalError, BoolType, one}
import firrtl.annotations._
import firrtl.analyses.InstanceGraph
import firrtl.transforms.TopWiring._
import freechips.rocketchip.util.property._
import freechips.rocketchip.util.WideCounter
import freechips.rocketchip.config.{Parameters, Field}
import midas.{EnableAutoCounter, AutoCounterUsePrintfImpl}
import midas.widgets._
import midas.targetutils._
import midas.passes.Utils.{widx, wsub}
import midas.passes.fame.{WireChannel, FAMEChannelConnectionAnnotation, And, Or, Negate}

import java.io._
import scala.io.Source
import collection.mutable

class FireSimPropertyLibrary extends BasePropertyLibrary {
  import chisel3._
  import chisel3.experimental.DataMirror.internal.isSynthesizable
  import chisel3.internal.sourceinfo.{SourceInfo}
  import chisel3.experimental.{annotate,ChiselAnnotation}
  def generateProperty(prop_param: BasePropertyParameters)(implicit sourceInfo: SourceInfo) {
    //requireIsHardware(prop_param.cond, "condition covered for counter is not hardware!")
    if (!(prop_param.cond.isLit) && chisel3.experimental.DataMirror.internal.isSynthesizable(prop_param.cond)) {
      dontTouch(prop_param.cond)
      dontTouch(chisel3.Module.reset)
      dontTouch(chisel3.Module.clock)
      annotate(new ChiselAnnotation {
        val implicitClock = chisel3.Module.clock
        val implicitReset = chisel3.Module.reset
        def toFirrtl = AutoCounterFirrtlAnnotation(prop_param.cond.toNamed,
                                                   implicitClock.toNamed.toTarget,
                                                   implicitReset.toNamed.toTarget,
                                                   prop_param.label,
                                                   prop_param.message,
                                                   coverGenerated = true)
      })
    }
  }
}
//=========================================================================


/**
  * Take the annotated cover points and convert them to counters
  */
class AutoCounterTransform(dir: File = new File("/tmp/"))
    (implicit p: Parameters) extends Transform with AutoCounterConsts {
  def inputForm: CircuitForm = LowForm
  def outputForm: CircuitForm = LowForm
  override def name = "[Golden Gate] AutoCounter Cover Transform"

  val enableTransform         = p(EnableAutoCounter)
  val usePrintfImplementation = p(AutoCounterUsePrintfImpl)
  //identify if there is a TracerV bridge to supply a trigger signal
  val hasTracerWidget         = p(midas.TraceTrigger)


  // Gates each auto-counter event with the associated reset, moving the
  // annotation's target to point at the new boolean (updatedAnnos).
  // This is used in both implementation strategies.
  private def gateEventsWithReset(coverTupleAnnoMap: Map[String, Seq[AutoCounterFirrtlAnnotation]],
                                  updatedAnnos: mutable.ArrayBuffer[AutoCounterFirrtlAnnotation])
                                 (mod: DefModule): DefModule = mod match {
    case m: Module if coverTupleAnnoMap.isDefinedAt(m.name) =>
      val coverAnnos = coverTupleAnnoMap(m.name)
      val mT = coverAnnos.head.enclosingModuleTarget
      val moduleNS = Namespace(mod)
      val addedStmts = coverAnnos.flatMap({ anno =>
        val eventName = moduleNS.newName(anno.label)
        updatedAnnos += anno.copy(target = mT.ref(eventName))
        Seq(DefWire(NoInfo, eventName, BoolType),
            Connect(NoInfo, WRef(eventName), And(Negate(WRef(anno.reset.ref)), WRef(anno.target.ref))))
      })
      m.copy(body = Block(m.body, addedStmts:_*))
      case o => o
  }

  private def onModulePrintfImpl(coverTupleAnnoMap: Map[String, Seq[AutoCounterFirrtlAnnotation]],
                                 hasTracerWidget: Boolean = false,
                                 addedAnnos: mutable.ArrayBuffer[Annotation])
                                (mod: DefModule): DefModule = mod match {
    case m: Module if coverTupleAnnoMap.isDefinedAt(m.name) =>
      val coverAnnos = coverTupleAnnoMap(m.name)
      val mT = coverAnnos.head.enclosingModuleTarget
      val moduleNS = Namespace(mod)

      // Forward declare a boolean for the trigger. Use the Wiring transform to bring it in later
      val triggerName = moduleNS.newName("trigger")
      val trigger = DefWire(NoInfo, triggerName, BoolType)
      val addedStmts = new mutable.ArrayBuffer[Statement]
      addedStmts ++= Seq(trigger, Connect(NoInfo, WRef(trigger), one))
      if (hasTracerWidget) {
        addedAnnos += SinkAnnotation(mT.ref(triggerName).toNamed, "trace_trigger")
      }
      val countType = UIntType(IntWidth(64))
      val zeroLit = UIntLiteral(0, IntWidth(64))
      val oneLit = UIntLiteral(1, IntWidth(64))

      addedStmts ++= coverAnnos.flatMap({ case AutoCounterFirrtlAnnotation(target, clock, reset, label, _, _) =>
        val countName = moduleNS.newName(label + "_counter")
        val count = DefRegister(NoInfo, countName, countType, WRef(clock.ref), WRef(reset.ref), zeroLit)
        val plusOneName = moduleNS.newName(label + "_plusOne")
        val plusOne = DefNode(NoInfo, plusOneName, DoPrim(PrimOps.Add, Seq(WRef(count), oneLit), Seq.empty, countType))
        val countUpdate = Connect(NoInfo, WRef(count), Mux(WRef(target.ref), WRef(plusOne), WRef(count), countType))
        val printFormat = StringLit(s"""[AutoCounter] $label: %d\n""")
        val printStmt = Print(NoInfo, printFormat, Seq(WRef(count)),
                              WRef(clock.ref), And(WRef(trigger), WRef(target.ref)))
        addedAnnos += SynthPrintfAnnotation(Seq(Seq(mT.ref(countName))), mT, printFormat.string, Some(target.ref + "_print"))
        Seq(count, plusOne, printStmt, countUpdate)
      })
      m.copy(body = Block(m.body, addedStmts:_*))
    case o => o
  }

  private def implementViaPrintf(
      state: CircuitState,
      hasTracerWidget: Boolean,
      eventModuleMap: Map[String, Seq[AutoCounterFirrtlAnnotation]]): CircuitState = {

    val addedAnnos = new mutable.ArrayBuffer[Annotation]()
    val updatedModules = state.circuit.modules.map(
      onModulePrintfImpl(eventModuleMap, hasTracerWidget, addedAnnos))
    state.copy(circuit = state.circuit.copy(modules = updatedModules),
               annotations = state.annotations ++ addedAnnos)
  }

  private def implementViaBridge(
      state: CircuitState,
      hasTracerWidget: Boolean,
      eventModuleMap: Map[String, Seq[AutoCounterFirrtlAnnotation]]): CircuitState = {

    val labelMap = eventModuleMap.values.flatten.map(anno => anno.target -> anno.label).toMap
    val bridgeTopWiringAnnos = eventModuleMap.values.flatten.map(
      anno => BridgeTopWiringAnnotation(anno.target, anno.clock))

    val topWiringPrefix = "autocounter_"
    val wiredState = (new BridgeTopWiring(topWiringPrefix)).execute(
      state.copy(annotations = state.annotations ++ bridgeTopWiringAnnos))
    val outputAnnos = wiredState.annotations.collect({ case a: BridgeTopWiringOutputAnnotation => a })
    val groupedOutputs = outputAnnos.groupBy(_.clockPort)

    val bridgeAnnos = for ((clockRT, oAnnos) <- groupedOutputs) yield {
      val fccaAnnos = oAnnos.map({ anno =>
        FAMEChannelConnectionAnnotation.source(
          anno.topSink.ref,
          WireChannel,
          Some(anno.clockPort),
          Seq(anno.topSink))
      })

      val labels = oAnnos.map({ anno =>
        val pathlessLabel = labelMap(anno.pathlessSource)
        val instPath = anno.absoluteSource.circuit +: anno.absoluteSource.asPath.map(_._1.value)
        anno.topSink.ref -> (pathlessLabel +: instPath).mkString("_")
      })

      val bridgeAnno = BridgeIOAnnotation(
        target = ModuleTarget(state.circuit.main, state.circuit.main).ref(topWiringPrefix.stripSuffix("_")),
        widget = (p: Parameters) => new AutoCounterBridgeModule(labels, hasTracerWidget)(p),
        channelNames = fccaAnnos.map(_.globalName)
      )
      bridgeAnno +: fccaAnnos
    }
    val cleanedAnnotations = wiredState.annotations.flatMap({
      case a: BridgeTopWiringOutputAnnotation => None
      case o => Some(o)
    })
    wiredState.copy(annotations = cleanedAnnotations ++ bridgeAnnos.flatten)
  }

  def doTransform(state: CircuitState): CircuitState = {
    //select/filter which modules do we want to actually look at, and generate counters for
    //this can be done in one of two way:
    //1. Using an input file called `covermodules.txt` in a directory declared in the transform concstructor
    //2. Using chisel annotations to be added in the Platform Config (in SimConfigs.scala). The annotations are
    //   of the form AutoCounterModuleAnnotation("ModuleName")
    val modulesfile = new File(dir,"autocounter-covermodules.txt")
    val moduleAnnos     = new mutable.ArrayBuffer[AutoCounterCoverModuleFirrtlAnnotation]()
    val counterAnnos    = new mutable.ArrayBuffer[AutoCounterFirrtlAnnotation]()
    val remainingAnnos  = new mutable.ArrayBuffer[Annotation]()
    if (modulesfile.exists()) {
      val sourcefile = scala.io.Source.fromFile(modulesfile.getPath())
      val covermodulesnames = (for (line <- sourcefile.getLines()) yield line).toList
      sourcefile.close()
      moduleAnnos ++= covermodulesnames.map {m: String => AutoCounterCoverModuleFirrtlAnnotation(ModuleTarget(state.circuit.main,m))}
    }
    state.annotations.foreach {
      case a: AutoCounterCoverModuleFirrtlAnnotation => moduleAnnos += a
      case a: AutoCounterFirrtlAnnotation => counterAnnos += a
      case o => remainingAnnos += o
    }

    //extract the module names from the methods mentioned previously
    val covermodulesnames = moduleAnnos.map(_.target.module).distinct

    //collect annotations for manually annotated AutoCounter perf counters
    val filteredCounterAnnos =  counterAnnos.filter(_.shouldBeIncluded(covermodulesnames))

    // group the selected signal by modules, and attach label from the cover point to each signal
    val selectedsignals = filteredCounterAnnos.groupBy(_.enclosingModule)

    if (!selectedsignals.isEmpty) {
      println("[AutoCounter] AutoCounter signals are:")
      selectedsignals.foreach({ case (modName, localEvents) =>
        println(s"  Module ${modName}")
        localEvents.foreach({ anno => println(s"   ${anno.label}: ${anno.message}") })
      })

      // Common preprocessing: gate all annotated events with their associated reset
      val updatedAnnos = new mutable.ArrayBuffer[AutoCounterFirrtlAnnotation]()
      val updatedModules = state.circuit.modules.map((gateEventsWithReset(selectedsignals, updatedAnnos)))
      val eventModuleMap = updatedAnnos.groupBy(_.enclosingModule)
      val preppedState = state.copy(circuit = state.circuit.copy(modules = updatedModules),
                                    annotations = remainingAnnos)

      if (usePrintfImplementation) {
        implementViaPrintf(preppedState, hasTracerWidget, eventModuleMap)
      } else {
        implementViaBridge(preppedState, hasTracerWidget, eventModuleMap)
      }
    } else { state }
  }

  def execute(state: CircuitState): CircuitState = {
    if (enableTransform) doTransform(state) else state
  }
}
