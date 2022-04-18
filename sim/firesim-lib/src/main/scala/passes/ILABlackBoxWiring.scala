//See LICENSE for license details
// See LICENSE for license details.

package firesim.passes

import midas.EnableAutoILA
import midas.targetutils.FirrtlFpgaDebugAnnotation
import midas.stage.{OutputFileBuilder, GoldenGateOutputFileAnnotation}
import midas.stage.phases.ConfigParametersAnnotation

import firrtl._
import firrtl.ir._
import firrtl.passes._
import firrtl.annotations._
import firrtl.stage.Forms
import firrtl.transforms.TopWiring._
import freechips.rocketchip.config.{Parameters, Field}

import java.io._
import scala.io.Source
import collection.mutable

case object ILADepthKey extends Field[Int](1024)

/** Add ports punch out annotated ports out to the toplevel of the circuit.
    This also has an option to pass a function as a parmeter to generate custom output files as a result of the additional ports
  * @note This *does* work for deduped modules
  */
class ILAWiringTransform extends Transform with DependencyAPIMigration {
  override def name = "[FireSim] ILA Wiring Transform"

  type InstPath = Seq[String]

  val addedFileAnnos = new mutable.ArrayBuffer[GoldenGateOutputFileAnnotation]

  override def prerequisites = Forms.MidForm
  override def optionalPrerequisites = Seq.empty
  // We want this pass to run before any emitters
  override def optionalPrerequisiteOf = Forms.HighEmitters ++ Forms.LowFormOptimized.filterNot(Forms.LowForm.contains)

  override def invalidates(a: Transform): Boolean = a match {
    case InferTypes | ResolveKinds | ResolveFlows | ExpandConnects => true
    case _ => false
  }

  def execute(state: CircuitState): CircuitState = { // Will probably want to refactor what's inside here to other methods in the pass
    val p = state.annotations.collectFirst({ case ConfigParametersAnnotation(p)  => p }).get
    val enableTransform = p(EnableAutoILA)
    val dataDepth = p(ILADepthKey)
    
    val ilaAnnos = state.annotations.collect {
      case a @ (_: FirrtlFpgaDebugAnnotation) if enableTransform => a
    }

    // Map debug annotations to top wiring annotations
    val targetAnnos = ilaAnnos match {
      case p => p.map { case FirrtlFpgaDebugAnnotation(target) => TopWiringAnnotation(target, s"ila_")  }
    }

    // Sneak out mapping, which has information about port widths needed for the ILA Tcl file, from a "dummy" TopWiringOutputFilesAnnotation
    // Code for this taken from  https://github.com/firesim/firesim/blob/master/sim/midas/src/main/scala/midas/passes/BridgeTopWiring.scala#L133

    //var topLevelOutputs = Seq[TopWiringMapping]()
    // Think this can be the same type as mapping, rather than TopWiringMapping case class
    var extractedMappings = Seq[((ComponentName, Type, Boolean, InstPath, String), Int)]
    def wiringAnnoOutputFunc(td: String,
                             mappings:  Seq[((ComponentName, Type, Boolean, Seq[String], String), Int)],
                             state: CircuitState): CircuitState = {
      //topLevelOutputs = mappings.unzip._1.map(inscrutable5Tuple => TopWiringMapping(inscrutable5Tuple._1, inscrutable5Tuple._4.dropRight(1)))
      // Don't think we need to do the above manipulation
      extractedMappings = mappings
      state
    }

    val topWiringOFileAnno = TopWiringOutputFilesAnnotation("unused", wiringAnnoOutputFunc)

    // Create a new top with the old wired port list
    val oldTop = state.circuit.modules.collectFirst({ case m: Module if m.name == state.circuit.main => m }).get
    // Include the "dummy" TopWiringOutputFilesAnnotation in the TopWiringTransform execution
    val wiredState = (new TopWiringTransform).execute(state.copy(annotations = state.annotations ++ targetAnnos ++ topWiringOFileAnno))
    val newTop = wiredState.circuit.modules.collectFirst({ case m: Module if m.name == wiredState.circuit.main => m }).get
    val newPorts = newTop.ports - oldTop.ports
    val newTopWithOldPorts = newTop.copy(ports = oldTop.ports)

    // Old top-level ports were outputs. To blackboxed ILA, they will be inputs. Flip the old ports.
    def flipPort(p: Port): Port =  p match {
      case  Port(_, _, Input, _) => Port(_, _, Output, _)
      case  Port(_, _, Output, _) => Port(_, _, Input, _)
    }
    val flippedPorts = newPorts.map(flipPort)
 
    // Extract probe information from mapping 5-tuple and create string list of probes to be injected in the output tcl string literal.
    extractedMappings.map { case ((cname, tpe, _, path, prefix), index) =>
      val probeWidth = tpe match { case GroundType(IntWidth(w)) => w }
      val probeWidth1 = probewidth - 1
      val probeNum = index
      val probePortName = prefix + path.mkString("_")

      // This is how it was done using the append method.
      //tclOutputFile.append(s"CONFIG.C_PROBE$probenum" ++ s"_WIDTH {$probewidth} ")
      //tclOutputFile.append(s"CONFIG.C_PROBE$probenum" ++ s"_MU_CNT {$probetriggers} ")
  

    if (extractedMappings.isEmpty) {
      // If the mappings are empty, we append a single, 1-width probe. Why? Doesn't an empty mapping mean nothing was broken out?
      // This is how it was done using the append method.
      //tclOutputFile.append(s"CONFIG.C_PROBE0" ++ s"_WIDTH {1} ")
      //tclOutputFile.append(s"CONFIG.C_PROBE0" ++ s"_MU_CNT {$probetriggers} ")
    }

    // Output ILA Tcl
    GoldenGateOutputFileAnnotation.annotateFromChisel(
      s"""|
          | create_project managed_ip_project $$CL_DIR/ip/firesim_ila_ip/managed_ip_project -part xcvu9p-flgb2104-2-i -ip -force # should this change for local FPGA part?
          | set_property simulator_language Verilog [current_project]
          | set_property target_simulator XSim [current_project]
          | create_ip -name ila -vendor xilinx.com -library ip -version 6.2 -module_name ila_firesim_0 -dir $$CL_DIR/ip/firesim_ila_ip -force
          | set_property -dict [list $probesList CONFIG.C_NUM_OF_PROBES {$numprobes} CONFIG.C_DATA_DEPTH {$dataDepth} CONFIG.C_TRIGOUT_EN {false} CONFIG.C_EN_STRG_QUAL {1} CONFIG.C_ADV_TRIGGER {true} CONFIG.C_TRIGIN_EN {false} CONFIG.ALL_PROBE_SAME_MU_CNT {$probetriggers}] [get_ips ila_firesim_0]
          | generate_target {instantiation_template} [get_files $$CL_DIR/ip/firesim_ila_ip/ila_firesim_0/ila_firesim_0.xci] 
          | generate_target all [get_files  $$CL_DIR/ip/firesim_ila_ip/ila_firesim_0/ila_firesim_0.xci]
          | export_ip_user_files -of_objects [get_files $$CL_DIR/ip/firesim_ila_ip/ila_0/ila_firesim_0.xci] -no_script -sync -force -quiet
          | create_ip_run [get_files -of_objects [get_fileset sources_1] $$CL_DIR/ip/firesim_ila_ip/ila_firesim_0/ila_firesim_0.xci]
          | launch_runs -jobs 8 ila_firesim_0_synth_1
          | wait_on_run ila_firesim_0_synth_1
          | export_simulation -of_objects [get_files $$CL_DIR/ip/firesim_ila_ip/ila_firesim_0/ila_firesim_0.xci] -directory $$CL_DIR/ip/firesim_ila_ip/ip_user_files/sim_scripts -ip_user_files_dir $$CL_DIR/ip/firesim_ila_ip/ip_user_files -ipstatic_source_dir $$CL_DIR/ip/firesim_ila_ip/ip_user_files/ipstatic -lib_map_path [list {modelsim=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/modelsim} {questa=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/questa} {ies=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/ies} {vcs=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/vcs} {riviera=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/riviera}] -use_ip_compiled_libs -force -quiet
          |""".stripMargin,
      s".ila_insert_vivado.tcl"
    )

    // Generate ILA blackbox submodule.
    val ilaBlackbox = ExtModule(
        info = NoInfo,
        name = "ila_firesim_0" // Taken from ilaInstOutputFile in old pass.
        ports = flippedPorts
        defname = "ila_firesim_0",
        params = Nil)
      )

    // Re-wire connect statements to ILA submodule
    def rewireConnects(s: Statement) : Statement = s.map(rewireConnects) match { // Not sure if need the recursive mapping
      // Connect(info, loc, expr)
      // WRef(name, type, kind, flow)
      // case c@ ...
      // grep for WSubfield and will see syntax. WSubfield is the thing to use to refer to the name of the submodule, give it a port name.
      case Connect (info, WRef(portName, _, PortKind, _), rhs) if newPorts(portName) => //c.copy(loc=WRef(WSubfield(submodule instance name)) //Connect(_, WRef(<reference to the ILA submodule>), rhs) // How do I reference the ILA submodule in WRef?
      case o => o
    }
    
    // Top-level module with rewired connect statements
    val rewiredTop = newTopWithOldPorts.copy(body = newTopWithOldPorts.body.map(rewireConnects)) // Try this first.
    //val rewiredTop = newTopWithOldPorts.mapStmt(rewireConnects) // Think this might work if mapStmt does what I think

    // Replace top-level module in circuit with rewired connections. How to do this?
    //map over modules and pattern match
    val newModules

    // Create new new state and circuit with rewired top-level module and blackbox added 
    // We should only append the blackbox module if mapping was empty. 
    val newCircuit = state.circuit.copy(modules = newModules ++ ilaBlackbox)
    val newState = state.copy(circuit = newCircuit)

    val cleanedAnnos = wiredState.annotations.filter {
      //case a: TopWiringOutputFilesAnnotation => false // don't think we need file annotations now.
      case a: FirrtlFpgaDebugAnnotation => false
      case _ => true
    }

    newState.copy(annotations = cleanedAnnos ++ addedFileAnnos)
  }
}
