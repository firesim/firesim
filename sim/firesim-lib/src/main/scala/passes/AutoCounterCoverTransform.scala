// See LICENSE for license details.

package firesim.passes

import firrtl._
import firrtl.ir._
import firrtl.passes._
import firrtl.passes.wiring.{WiringTransform}
import firrtl.Utils.throwInternalError
import firrtl.annotations._
import firrtl.analyses.InstanceGraph
import freechips.rocketchip.util.property._
import freechips.rocketchip.util.WideCounter
import firesim.bridges._
import midas.targetutils._

 import java.io._
import scala.io.Source
import collection.mutable


 //====================MOVE TO A UTILS PLACE? ALTHOUGH THESE ARE ALL PLATFORM STUFF=======================
/*
case class AutoCounterCoverAnnotation(target: ReferenceTarget, label: String, message: String) extends
    SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(target = n)
}

case class AutoCounterFirrtlAnnotation(target: ReferenceTarget, label: String, message: String) extends
    SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(target = n)
}

case class AutoCounterCoverModuleAnnotation(target: ModuleTarget) extends
    SingleTargetAnnotation[ModuleTarget] {
  def duplicate(n: ModuleTarget) = this.copy(target = n)
}

import chisel3.experimental.ChiselAnnotation
case class AutoCounterModuleAnnotation(target: String) extends ChiselAnnotation {
  //TODO: fix the CircuitName arguemnt of ModuleTarget after chisel implements Target
  //It currently doesn't matter since the transform throws away the circuit name
  def toFirrtl =  AutoCounterCoverModuleAnnotation(ModuleTarget("",target))
}

case class AutoCounterAnnotation(target: chisel3.Data, label: String, message: String) extends ChiselAnnotation {
  def toFirrtl =  AutoCounterFirrtlAnnotation(target.toNamed.toTarget, label, message)
}

object PerfCounter {
  def apply(target: chisel3.Data, label: String, message: String): Unit = {
    chisel3.experimental.annotate(AutoCounterAnnotation(target, label, message))
  }
}
*/

class FireSimPropertyLibrary() extends BasePropertyLibrary {
  import chisel3._
  import chisel3.experimental.DataMirror.internal.isSynthesizable
  import chisel3.internal.sourceinfo.{SourceInfo}
  import chisel3.core.{annotate,ChiselAnnotation}
  def generateProperty(prop_param: BasePropertyParameters)(implicit sourceInfo: SourceInfo) {
    //requireIsHardware(prop_param.cond, "condition covered for counter is not hardware!")
    if (!(prop_param.cond.isLit) && chisel3.experimental.DataMirror.internal.isSynthesizable(prop_param.cond)) {
      annotate(new ChiselAnnotation { def toFirrtl = AutoCounterCoverAnnotation(prop_param.cond.toNamed, prop_param.label, prop_param.message) })
    }
  }
}
//=========================================================================
 

/**
Take the annotated cover points and convert them to counters with synthesizable printfs
**/
class AutoCounterCoverTransform(dir: File = new File("/tmp/"), printcounter: Boolean = false) extends Transform {
  def inputForm: CircuitForm = LowForm
  def outputForm: CircuitForm = LowForm
  override def name = "[FireSim] AutoCounter Cover Transform"
  val newAnnos = mutable.ArrayBuffer.empty[Annotation]
  val autoCounterLabels = mutable.ArrayBuffer.empty[String]

  private def MakeCounter(label: String, hastracerwidget: Boolean = false): CircuitState = {
    import chisel3._
    import chisel3.experimental.MultiIOModule
    import chisel3.util.experimental.BoringUtils
    def countermodule() = new MultiIOModule {
      //override def desiredName = "AutoCounter"
      val cycle = RegInit(0.U(64.W))
      cycle := cycle + 1.U
      val in0 = IO(Input(Bool()))

      //connect the trigger from the tracer widget
      val trigger = Wire(Bool())
      hastracerwidget match {
        case true => BoringUtils.addSink(trigger, s"trace_trigger") 
        case _ => trigger := RegInit(true.B)
      }

      //*****if we ever want to use the trigger to reset the counters******
      //withReset(reset = ~trigger) {
      //  val count = WideCounter(64, in0)
      //}

      val count = WideCounter(64, in0)
      if (printcounter) {
        when (in0 & trigger) {
          printf(midas.targetutils.SynthesizePrintf(s"[AutoCounter] $label: %d cycle: %d\n",count, cycle))
        }
      } else {
          autoCounterLabels ++= Seq(s"AutoCounter_$label")
          BoringUtils.addSource(count, s"AutoCounter_$label")
      }
    }

    val chiselIR = chisel3.Driver.elaborate(() => countermodule())
    val annos = chiselIR.annotations.map(_.toFirrtl)
    val firrtlIR = chisel3.Driver.toFirrtl(chiselIR)
    val lowFirrtlIR = (new LowFirrtlCompiler()).compile(CircuitState(firrtlIR, ChirrtlForm, annos), Seq())
    lowFirrtlIR 
  } 

  private def onModule(topNS: Namespace, covertuples: Seq[(ReferenceTarget, String)], hastracerwidget: Boolean = false)(mod: Module): Seq[Module] = {

    val namespace = Namespace(mod)
    val resetRef = mod.ports.collectFirst { case Port(_,"reset",_,_) => WRef("reset") }

    //need some mutable lists
    val newMods = mutable.ArrayBuffer.empty[Module]
    val newInsts = mutable.ArrayBuffer.empty[WDefInstance]
    val newCons = mutable.ArrayBuffer.empty[Connect]

    //for each annotated signal within this module
    covertuples.foreach { case (target, label) =>
        //create counter
        val countermodstate = MakeCounter(label, hastracerwidget = hastracerwidget)
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
        //println(clocks)
        //create clock connection to the counter
        val clkCon = {
          val lhs = WSubField(WRef(inst.name), "clock")
          val rhs = WRef(clocks(0))
          Connect(NoInfo, lhs, rhs)
        }
        newCons += clkCon

        //create reset connection to the coutner
        val reset = resetRef.getOrElse(UIntLiteral(0, IntWidth(1)))
        val resetCon = Connect(NoInfo, WSubField(WRef(inst.name), "reset"), reset)
        newCons += resetCon

        //add to new connections list that will be added to the block
        //val allCons =  Seq(wcons, clkCon, resetCon)
        //newCons += allCons
     }

     //add new block of statements to the module (with the new instantiations and connections)
     val bodyx = Block(mod.body +: (newInsts ++ newCons))
     Seq(mod.copy(body = bodyx)) ++ newMods
   }

   private def fixupCircuit(circuit: Circuit): Circuit = {
     val passes = Seq(
       new WiringTransform,
       InferTypes,
       ResolveKinds,
       ResolveGenders
     )
     passes.foldLeft(circuit) { case (c: Circuit, p: Pass) => p.run(c) }
   }


   //create the appropriate perf counters target widget
   private def MakeAutoCounterWidget(topNS: Namespace, numcounters: Int, maincircuit: Circuit): Module = {

     import chisel3._
     import chisel3.util.experimental.BoringUtils
     import chisel3.experimental.MultiIOModule
     import midas.widgets._
     import freechips.rocketchip.config.{Parameters, Field}
     import firesim.bridges.{AutoCounterBundle, AutoCounterBridgeModule}

     //def targetwidgetmodule() = new BlackBox with Bridge[HostPortIO[AutoCounterBundle], AutoCounterBridgeModule] {
     def targetwidgetmodule() = new MultiIOModule with Bridge[HostPortIO[AutoCounterBundle], AutoCounterBridgeModule] {
       override def desiredName = "AutoCounterBridge"
       val io = IO(new AutoCounterBundle(numcounters))
       val bridgeIO = HostPort(io)

       case class AutoCounterBridgeConstArgs(numcounters: Int, autoCounterLabels: Seq[String])

       val constructorArg = Some(AutoCounterBridgeConstArgs(numcounters, autoCounterLabels)) 
 
       //wiring transform annotation to connect to the counters
       autoCounterLabels.zip(io.counters).foreach {
          case(label, counter) => BoringUtils.addSink(counter, label)
       } 
       generateAnnotations()
     } 


     val chiselIR = chisel3.Driver.elaborate(() => targetwidgetmodule())
     val annos = chiselIR.annotations.map(_.toFirrtl)
     val firrtlIR = chisel3.Driver.toFirrtl(chiselIR)
     val lowFirrtlIR = (new LowFirrtlCompiler()).compile(CircuitState(firrtlIR, ChirrtlForm, annos), Seq())


     val targetwidgetmod = lowFirrtlIR.circuit.modules match {
         case Seq(one: firrtl.ir.Module) => one
         case other => throwInternalError(s"Invalid resulting target widget modules ${other.map(_.name)}")
     }
     //add to new modules list that will be added to the circuit
     val newtargetwidgetname = topNS.newName(targetwidgetmod.name)
     val targetwidgetmodn = targetwidgetmod.copy(name = newtargetwidgetname)
     val renamemap = RenameMap(Map(ModuleTarget(lowFirrtlIR.circuit.main, targetwidgetmod.name) -> Seq(ModuleTarget(maincircuit.main, newtargetwidgetname))))
     newAnnos ++= lowFirrtlIR.annotations.toSeq.flatMap { case anno => anno.update(renamemap) }
     //instantiate the counter

     //TODO: Do I need to instantiate ths blackbox???????

     //return the new module
     targetwidgetmodn
   }



   //count the number of generated perf counters
   //create an appropriate widget IO
   //wire the counters to the widget
   private def AddAutoCounterWidget(circuit: Circuit): Circuit = {
     val topnamespace = Namespace(circuit)
     val instanceGraph = new InstanceGraph(circuit)
     //val instanceGraph = (new InstanceGraph(circuit)).graph
     val numcounters = instanceGraph.findInstancesInHierarchy("AutoCounter").size
     val widgetmod = MakeAutoCounterWidget(topnamespace, numcounters, circuit)

     val topSort = instanceGraph.moduleOrder
     val top = topSort.head.asInstanceOf[Module]
     val widgetInstName = topnamespace.newName(s"autocounter_target_widget_inst") // Helps debug
     val widgetInst = WDefInstance(NoInfo, widgetInstName, widgetmod.name, UnknownType)
     val bodyx = Block(top.body +: Seq(widgetInst))
     val newtop = top.copy(body = bodyx) 
     circuit.copy(modules = topSort.tail ++ Seq(newtop) ++ Seq(widgetmod)) 
   }



   def execute(state: CircuitState): CircuitState = {

    //collect annotation generate by the built in cover points in rocketchip
    //(or manually inserted annotations)
    val coverannos = state.annotations.collect {
      case a: AutoCounterCoverAnnotation => a
    }

    val autocounterannos = state.annotations.collect {
      case a: AutoCounterFirrtlAnnotation => a
    }


    val hastracerwidget = state.annotations.collect({ case midas.widgets.SerializableBridgeAnnotation(_,_,widget, _) => 
                                          widget match {
                                              case "firesim.bridges.TracerVBridgeModule" => true
                                              case _ => Nil
                                          }}).length > 0

    //select which modules do we want to actually look at, and generate counters for
    //this can be done in one of two way:
    //1. Using an input file called `covermodules.txt` in a directory declared in the transform concstructor
    //2. Using chisel annotations to be added in the Platform Config (in SimConfigs.scala). The annotations are
    //   of the form AutoCounterModuleAnnotation("ModuleName")
    val modulesfile = new File(dir,"covermodules.txt")
    val filemoduleannos = mutable.ArrayBuffer.empty[AutoCounterCoverModuleAnnotation]
    if (modulesfile.exists()) {
      val sourcefile = scala.io.Source.fromFile(modulesfile.getPath())
      val covermodulesnames = (for (line <- sourcefile.getLines()) yield line).toList
      sourcefile.close()
      filemoduleannos ++= covermodulesnames.map {m: String => AutoCounterCoverModuleAnnotation(ModuleTarget(state.circuit.main,m))}
    }
    val moduleannos = (state.annotations.collect {
      case a: AutoCounterCoverModuleAnnotation => a
    } ++ filemoduleannos).distinct

    //extract the module names from the methods mentioned previously 
    val covermodulesnames = moduleannos.map { case AutoCounterCoverModuleAnnotation(ModuleTarget(_,m)) => m }

    if (!covermodulesnames.isEmpty) {
      println("[AutoCounter]: Cover modules in AutoCounterCoverTransform:")
      println(covermodulesnames)

      //filter the cover annotations only by the modules that we want
      val filtercoverannos = coverannos.filter{ case AutoCounterCoverAnnotation(ReferenceTarget(_,modname,_,_,_),l,m) =>
                                           covermodulesnames.contains(modname) }

      val allcounterannos = filtercoverannos ++ autocounterannos
      //group the selected signal by modules, and attach label from the cover point to each signal
      val selectedsignals = allcounterannos.map { case AutoCounterCoverAnnotation(target,l,m) => (target, l) 
                                                  case AutoCounterFirrtlAnnotation(target,l,m) => (target, l)
                                            }
                                   .groupBy { case (ReferenceTarget(_,modname,_,_,_), l) => modname }


      //create counters for each of the Bools in the filtered cover functions
      val moduleNamespace = Namespace(state.circuit)
      val modulesx: Seq[DefModule] = state.circuit.modules.map {
        case mod: Module =>
          val covertuples = selectedsignals.getOrElse(mod.name, Seq())
          if (!covertuples.isEmpty) {
            val mods = onModule(moduleNamespace, covertuples, hastracerwidget = hastracerwidget)(mod)
            val newMods = mods.filter(_.name != mod.name)
            assert(newMods.size + 1 == mods.size) // Sanity check
            mods
          } else { Seq(mod) }
        case ext: ExtModule => Seq(ext)
      }.flatten

      
      val circuitwithwidget = AddAutoCounterWidget(state.circuit.copy(modules = modulesx))  
      state.copy(circuit = fixupCircuit(circuitwithwidget), annotations = state.annotations ++ newAnnos)
      
    } else { state }
  }
}

