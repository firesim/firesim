// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.ir._
import firrtl.passes._
import firrtl.passes.wiring._
import firrtl.Utils.throwInternalError
import firrtl.annotations._
import firrtl.analyses.InstanceGraph
import firrtl.transforms.TopWiring._
import freechips.rocketchip.util.property._
import freechips.rocketchip.util.WideCounter
import freechips.rocketchip.config.{Parameters, Field}
import midas.widgets._
import midas.targetutils._
import midas.passes.Utils.{widx, wsub}
import midas.passes.fame.FAMEChannelConnectionAnnotation

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
      annotate(new ChiselAnnotation { def toFirrtl = AutoCounterCoverAnnotation(prop_param.cond.toNamed, prop_param.label, prop_param.message) })
    }
  }
}
//=========================================================================

/**
Take the annotated cover points and convert them to counters
**/
class AutoCounterTransform(dir: File = new File("/tmp/"), printcounter: Boolean = false)
    (implicit p: Parameters) extends Transform with AutoCounterConsts {
  def inputForm: CircuitForm = LowForm
  def outputForm: CircuitForm = LowForm
  override def name = "[FireSim] AutoCounter Cover Transform"
  val newAnnos = mutable.ArrayBuffer.empty[Annotation]
  val autoCounterLabels = mutable.ArrayBuffer.empty[String]
  val autoCounterMods = mutable.ArrayBuffer.empty[Module]
  val autoCounterLabelsSourceMap = mutable.Map.empty[String, String]
  val autoCounterReadableLabels = mutable.ArrayBuffer.empty[String]
  val autoCounterReadableLabelsMap = mutable.Map.empty[String, String]
  val autoCounterPortsMap = mutable.Map.empty[Int, String]
  val autoCounterLabelsMap = mutable.Map.empty[String, String]

  private def makeCounter(label: String, hasTracerWidget: Boolean = false): CircuitState = {
    import chisel3._
    import chisel3.core.MultiIOModule
    import chisel3.experimental.ChiselAnnotation
    import chisel3.util.experimental.BoringUtils
    def countermodule() = new MultiIOModule {
      //override def desiredName = "AutoCounter"
      val in0 = IO(Input(Bool()))

      //connect the trigger from the tracer widget
      val trigger = WireDefault(true.B)
      hasTracerWidget match {
        case true => BoringUtils.addSink(trigger, s"trace_trigger")
        case _ => trigger := true.B
      }

      //*****if we ever want to use the trigger to reset the counters******
      //withReset(reset = ~trigger) {
      //  val countfun = WideCounter(64, in0)
      //}

      val countfun = WideCounter(64, in0)
      val count = Wire(UInt(64.W))
      count := countfun
      if (printcounter) {
        when (in0 & trigger) {
          printf(midas.targetutils.SynthesizePrintf(s"[AutoCounter] $label: %d\n",count))
        }
      } else {
          chisel3.core.dontTouch(count)
          chisel3.experimental.annotate(new ChiselAnnotation { def toFirrtl = TopWiringAnnotation(count.toNamed, s"autocounter_") })
          //********In the future, when BoringUtils will be more rubust with TargetRefs***********
          //autoCounterLabels ++= Seq(s"AutoCounter_$label")
          //BoringUtils.addSource(count, s"AutoCounter_$label")
      }
    }

    val chiselIR = chisel3.Driver.elaborate(() => countermodule())
    val annos = chiselIR.annotations.map(_.toFirrtl)
    val firrtlIR = chisel3.Driver.toFirrtl(chiselIR)
    val lowFirrtlIR = (new LowFirrtlCompiler()).compile(CircuitState(firrtlIR, ChirrtlForm, annos), Seq())
    lowFirrtlIR
  }

  private def onModule(topNS: Namespace, covertuples: Seq[(ReferenceTarget, String)], hasTracerWidget: Boolean = false)(mod: Module): Seq[Module] = {

    val namespace = Namespace(mod)
    val resetRef = mod.ports.collectFirst { case Port(_,"reset",_,_) => WRef("reset") }

    //need some mutable lists
    val newMods = mutable.ArrayBuffer.empty[Module]
    val newInsts = mutable.ArrayBuffer.empty[WDefInstance]
    val newCons = mutable.ArrayBuffer.empty[Connect]

    //for each annotated signal within this module
    covertuples.foreach { case (target, label) =>
        //create counter
        val countermodstate = makeCounter(label, hasTracerWidget = hasTracerWidget)
        val countermod = countermodstate.circuit.modules match {
            case Seq(one: firrtl.ir.Module) => one
            case other => throwInternalError(s"Invalid resulting modules ${other.map(_.name)}")
        }
        //add to new modules list that will be added to the circuit
        val newmodulename = topNS.newName(countermod.name)
        val countermodn = countermod.copy(name = newmodulename)
        val maincircuitname = target.circuitOpt.get
        val renamemap = RenameMap(Map(ModuleTarget(countermodstate.circuit.main, countermod.name) -> Seq(ModuleTarget(maincircuitname, newmodulename))))
        newMods += countermodn
        autoCounterMods += countermodn
        autoCounterLabelsMap += countermodn.name -> label
        newAnnos ++= countermodstate.annotations.toSeq.flatMap { case anno => anno.update(renamemap) }
        //instantiate the counter
        val instName = namespace.newName(s"autocounter_" + label) // Helps debug
        val inst = WDefInstance(NoInfo, instName, countermodn.name, UnknownType)
        //add to new instances list that will be added to the block
        newInsts += inst

        //create input connection to the counter
        val wcons = {
          val lhs = WSubField(WRef(inst.name),"in0")
          val rhs = WRef(target.name)
          Connect(NoInfo, lhs, rhs)
        }
        newCons += wcons

        val clocks = mod.ports.collect({ case Port(_,name,_,ClockType) => name})
        //create clock connection to the counter
        val clkCon = {
          val lhs = WSubField(WRef(inst.name), "clock")
          val rhs = WRef(clocks(0))
          Connect(NoInfo, lhs, rhs)
        }
        newCons += clkCon

        //create reset connection to the counter
        val reset = resetRef.getOrElse(UIntLiteral(0, IntWidth(1)))
        val resetCon = Connect(NoInfo, WSubField(WRef(inst.name), "reset"), reset)
        newCons += resetCon
     }

     //add new block of statements to the module (with the new instantiations and connections)
     val bodyx = Block(mod.body +: (newInsts ++ newCons))
     Seq(mod.copy(body = bodyx)) ++ newMods
   }

   private def fixupCircuit(instate: CircuitState): CircuitState = {
     val xforms = Seq(
          //new WiringTransform,
          new ResolveAndCheck
     )
     (xforms foldLeft instate)((in, xform) =>
      xform runTransform in).copy(form=outputForm)
   }

  //create the appropriate perf counters target widget
  private def makeAutoCounterWidget(topNS: Namespace, numCounters: Int, maincircuit: Circuit, hasTracerWidget: Boolean = false): (ExtModule, Seq[Annotation]) = {
    val bridgeName = topNS.newName("AutoCounterBridge")
    val bridgeMT = ModuleTarget(maincircuit.main, bridgeName)
    val ioName = "counters"
    val portList = Seq.tabulate(numCounters)(idx =>
      Port(NoInfo, s"${ioName}_${idx}", Input, UIntType(IntWidth(counterWidth))))
    val extModule = ExtModule(NoInfo, bridgeName, portList, bridgeName, Seq())
    val channelAnnos = portList.map(port =>
      FAMEChannelConnectionAnnotation.source(port.name, fame.WireChannel, Seq(bridgeMT.ref(port.name))))
    val bridgeCtorArg = AutoCounterBridgeConstArgs(numCounters, autoCounterPortsMap, hasTracerWidget)
    val bridgeAnnotation = InMemoryBridgeAnnotation(bridgeMT, channelAnnos.map(_.globalName),
      (p: Parameters) => new AutoCounterBridgeModule(bridgeCtorArg)(p))
    (extModule, bridgeAnnotation +: channelAnnos)
  }

   private def CreateTopCounterSources(instancepaths: Seq[Seq[WDefInstance]], state: CircuitState, topnamespace: Namespace): Seq[Statement] = {

      instancepaths.flatMap { case instpath =>
                          val instpathnames = instpath.map {case WDefInstance(_,name,_,_) => name}
                          val path = instpathnames.tail.tail.mkString("_")
                          val portname = s"autocounter_" + path + "_count"
                          val fullportname = instpathnames.tail.head + "." + portname
                          val mod = instpath.last.module // WDefInstance.module is a string, should be equivalent to the module name
                          val oldlabel = autoCounterLabelsMap(mod)
                          val readablelabel = oldlabel + s"[" + instpathnames.dropRight(1).mkString(".") + s"]"
                          val newlabel = oldlabel + s"_" + instpathnames.dropRight(1).mkString("_")

                          //When the wiring transform gets fixed
                          //=======================================================
                          //newAnnos += SourceAnnotation(ModuleTarget(state.circuit.main, instpath.head.module).ref(fullportname).toNamed, s"AutoCounter_$newlabel")
                          //=======================================================
                          //Instead of wiring transform, manually connect the counter wire source from the DUT side
                          val sourceref = WSubField(WRef(instpathnames.tail.head), portname)
                          val wirename = topnamespace.newName(newlabel)
                          val medref = WRef(wirename)
                          autoCounterLabelsSourceMap += newlabel -> wirename
                          autoCounterReadableLabelsMap += newlabel -> readablelabel
                          Seq(DefWire(NoInfo, wirename, UIntType(IntWidth(64))), Connect(NoInfo, medref, sourceref))
      }
   }


   //count the number of generated perf counters
   //create an appropriate widget IO
   //wire the counters to the widget
   private def AddAutoCounterWidget(state: CircuitState, hasTracerWidget: Boolean = false): CircuitState = {

     //need to "remember/save" the "old/original" top level module, since the TopWiring transform
     //punches signals out all the way to the top, and we want them punched out only
     //through the DUT
     val oldinstanceGraph = new InstanceGraph(state.circuit)
     val top = oldinstanceGraph.moduleOrder.head.asInstanceOf[Module]


     //punch out counter signals to the top
     def topwiringtransform = new TopWiringTransform
     val newstate: CircuitState = topwiringtransform.execute(state.copy(annotations = state.annotations ++ newAnnos))

     //cleanup the topwiring annotations
     val srcannos = newAnnos.collect {
       case a: TopWiringAnnotation => a
     }
     newAnnos --= srcannos

     //Find the relevant ports/wires that were punched out to the top by finding the autocounter instances
     val circuit = newstate.circuit
     val topnamespace = Namespace(circuit)
     val instanceGraph = new InstanceGraph(circuit)

     val autocounterinsts = autoCounterMods.flatMap { case mod => instanceGraph.findInstancesInHierarchy(mod.name) }

     val numcounters = autocounterinsts.size
     val sourceconnections = CreateTopCounterSources(autocounterinsts, newstate, topnamespace)


     //create the bridge module (widget)
     val (widgetMod, bridgeAnnos) = makeAutoCounterWidget(topnamespace, numcounters, newstate.circuit, hasTracerWidget)

     val topSort = instanceGraph.moduleOrder

     val widgetInstName = topnamespace.newName(s"AutoCounterBridge_inst") // Helps debug
     val widgetInst = WDefInstance(NoInfo, widgetInstName, widgetMod.name, UnknownType)
     val counterPorts = widgetMod.ports.map(_.name)


     //When the wiring transform gets fixed
     //wiring transform annotation to connect to the counters
     //================================================
     //autoCounterLabels.zip(counterports).foreach {
     //   case(label, counterport) => {
     //       newAnnos += SinkAnnotation(ModuleTarget(newstate.circuit.main, newtop.name).ref(widgetInst.name).field(counterport).toNamed, label)
     //   }
     //}
     //================================================


     //Instead of wiring transform, manually connect the counter wire sinks on the Bridge side
     val sinkconnections = autoCounterLabelsSourceMap.keys.zipWithIndex.flatMap {
            case(label,i) => {
                val sinkref = wsub(WRef(widgetInst.name), counterPorts(i))
                val medref = WRef(autoCounterLabelsSourceMap(label))
                //autoCounterPortsMap += i -> autoCounterReadableLabelsMap(label)
                autoCounterPortsMap += i -> label
                Seq(Connect(NoInfo, sinkref, medref))
            }
     }
     val newstatements = Seq(widgetInst) ++ sourceconnections ++ sinkconnections

     //update the body of the top level module
     val bodyx = Block(top.body +: newstatements)
     val newtop = top.copy(body = bodyx)

     newstate.copy(
       circuit = newstate.circuit.copy(modules = topSort.tail ++ Seq(newtop, widgetMod)),
       annotations = state.annotations ++ bridgeAnnos)
   }

   def execute(state: CircuitState): CircuitState = {

    //collect annotation generate by the built in cover points in rocketchip
    //(or manually inserted annotations)
    val coverannos = state.annotations.collect {
      case a: AutoCounterCoverAnnotation => a
    }

    //collect annotations for manually annotated AutoCounter perf counters
    val autocounterannos = state.annotations.collect {
      case a: AutoCounterFirrtlAnnotation => a
    }

    //identify if there is a TracerV bridge to supply a trigger signal
    val hasTracerWidget = p(midas.TraceTrigger)

    //----if we want to identify the tracer widget based on bridge annotations, we can use this code segment,
    //----but this requires the transform to be part of the firesim package rather than midas package
    //val hasTracerWidget = state.annotations.collect({ case midas.widgets.SerializableBridgeAnnotation(_,_,widget, _) =>
    //                                      widget match {
    //                                          case "firesim.bridges.TracerVBridgeModule" => true
    //                                          case _ => Nil
    //                                      }}).length > 0

    //select/filter which modules do we want to actually look at, and generate counters for
    //this can be done in one of two way:
    //1. Using an input file called `covermodules.txt` in a directory declared in the transform concstructor
    //2. Using chisel annotations to be added in the Platform Config (in SimConfigs.scala). The annotations are
    //   of the form AutoCounterModuleAnnotation("ModuleName")
    val modulesfile = new File(dir,"autocounter-covermodules.txt")
    val filemoduleannos = mutable.ArrayBuffer.empty[AutoCounterCoverModuleFirrtlAnnotation]
    if (modulesfile.exists()) {
      val sourcefile = scala.io.Source.fromFile(modulesfile.getPath())
      val covermodulesnames = (for (line <- sourcefile.getLines()) yield line).toList
      sourcefile.close()
      filemoduleannos ++= covermodulesnames.map {m: String => AutoCounterCoverModuleFirrtlAnnotation(ModuleTarget(state.circuit.main,m))}
    }
    val moduleannos = (state.annotations.collect {
      case a: AutoCounterCoverModuleFirrtlAnnotation => a
    } ++ filemoduleannos).distinct

    //extract the module names from the methods mentioned previously
    val covermodulesnames = moduleannos.map { case AutoCounterCoverModuleFirrtlAnnotation(ModuleTarget(_,m)) => m }

    if (!covermodulesnames.isEmpty) {
      println("[AutoCounter]: Cover modules in AutoCounterTransform:")
      println(covermodulesnames)
    }

    //filter the cover annotations only by the modules that we want
    val filtercoverannos = coverannos.filter{ case AutoCounterCoverAnnotation(ReferenceTarget(_,modname,_,_,_),l,m) =>
                                           covermodulesnames.contains(modname) }

    val allcounterannos = filtercoverannos ++ autocounterannos
    //group the selected signal by modules, and attach label from the cover point to each signal
    val selectedsignals = allcounterannos.map { case AutoCounterCoverAnnotation(target,l,m) => (target, l)
                                                case AutoCounterFirrtlAnnotation(target,l,m) => (target, l)
                                            }
                                   .groupBy { case (ReferenceTarget(_,modname,_,_,_), l) => modname }


    if (!selectedsignals.isEmpty) {
      println("[AutoCounter]: AutoCounter signals are:")
      println(selectedsignals)

      //create counters for each of the Bools in the filtered cover functions
      val moduleNamespace = Namespace(state.circuit)
      val modulesx: Seq[DefModule] = state.circuit.modules.map {
        case mod: Module =>
          val covertuples = selectedsignals.getOrElse(mod.name, Seq())
          if (!covertuples.isEmpty) {
            val mods = onModule(moduleNamespace, covertuples, hasTracerWidget = hasTracerWidget)(mod)
            val newMods = mods.filter(_.name != mod.name)
            assert(newMods.size + 1 == mods.size) // Sanity check
            mods
          } else { Seq(mod) }
        case ext: ExtModule => Seq(ext)
      }.flatten

      val statewithwidget = printcounter match {
        case true => state.copy(circuit = state.circuit.copy(modules = modulesx), annotations = state.annotations ++ newAnnos)
        case _ => AddAutoCounterWidget(state.copy(circuit = state.circuit.copy(modules = modulesx)), hasTracerWidget = hasTracerWidget)
      }

      fixupCircuit(statewithwidget)
    } else { state }
  }
}

