// See LICENSE for license details.

package firesim.passes

import midas.passes.FirrtlFpgaDebugAnnotation

import firrtl._
import firrtl.ir._
import firrtl.passes._
import firrtl.annotations._

import java.io._
import scala.io.Source
import collection.mutable

import firrtl.transforms.TopWiring._

/** Add ports punch out annotated ports out to the toplevel of the circuit.
    This also has an option to pass a function as a parmeter to generate custom output files as a result of the additional ports
  * @note This *does* work for deduped modules
  */
class ILATopWiringTransform(dir: File = new File("/tmp/")) extends Transform {
  def inputForm: CircuitForm = LowForm
  def outputForm: CircuitForm = LowForm
  override def name = "[FireSim] ILA Top Wiring Transform"

  type InstPath = Seq[String]

  def ILAWiringOutputFiles(dir: String, mapping: Seq[((ComponentName, Type, Boolean, InstPath, String), Int)], state: CircuitState): CircuitState = {

    //val targetFile: Option[String] = state.annotations.collectFirst { case ILADebugFileAnnotation(fn) => fn }

    //output vivado tcl file
    val tclOutputFile = new PrintWriter(new File(dir, "firesim_ila_insert_vivado.tcl" ))
    //output verilog 'include' file with ports
    val portsOutputFile = new PrintWriter(new File(dir, "firesim_ila_insert_ports.v" ))
    //output verilog 'include' file with wires
    val wiresOutputFile = new PrintWriter(new File(dir, "firesim_ila_insert_wires.v" ))
    //output verilog 'include' file with ila instantiation
    val ilaInstOutputFile = new PrintWriter(new File(dir, "firesim_ila_insert_inst.v" ))

    //vivado tcl prologue
    tclOutputFile.append(s"create_project managed_ip_project $$CL_DIR/ip/firesim_ila_ip/managed_ip_project -part xcvu9p-flgb2104-2-i -ip -force\n")
    tclOutputFile.append(s"set_property simulator_language Verilog [current_project]\n")
    tclOutputFile.append(s"set_property target_simulator XSim [current_project]\n")
    tclOutputFile.append(s"create_ip -name ila -vendor xilinx.com -library ip -version 6.2 -module_name ila_firesim_0 -dir $$CL_DIR/ip/firesim_ila_ip -force\n")
    tclOutputFile.append(s"set_property -dict [list ")


    if (mapping.nonEmpty) {
      //verilg wires `include file prologeu
      wiresOutputFile.append(s"//Automatically generated wire declarations for ILA connections\n")

      //verilog ila instantiation `include file prologue 
      ilaInstOutputFile.append(s"//Automatically generated ILA instantiation\n")
      ilaInstOutputFile.append(s"ila_firesim_0 CL_FIRESIM_DEBUG_WIRING_TRANSFORM (\n")
      ilaInstOutputFile.append(s"    .clk    (firesim_internal_clock)")
    }

    //body
    mapping map { case ((cname, tpe, _, path, prefix), index) =>
      //val probewidth = tpe.asInstanceOf[GroundType].width.asInstanceOf[IntWidth].width
      val probewidth = tpe match { case GroundType(IntWidth(w)) => w }
      val probewidth1 = probewidth - 1
      val probetriggers = 3
      val probenum = index
      val probeportname = prefix + path.mkString("_")

      //vivado tcl
      tclOutputFile.append(s"CONFIG.C_PROBE$probenum" ++ s"_WIDTH {$probewidth} ")
      tclOutputFile.append(s"CONFIG.C_PROBE$probenum" ++ s"_MU_CNT {$probetriggers} ")

      //verilog ports
      portsOutputFile.append(s"    .$probeportname($probeportname), \n")

      //verilog wires
      wiresOutputFile.append(s"wire [$probewidth1:0] $probeportname; \n")

      //verilog ila instantiation
      ilaInstOutputFile.append(s",\n    .probe$probenum ($probeportname)")
    }

    if (mapping.isEmpty) {
        //vivado tcl
        tclOutputFile.append(s"CONFIG.C_PROBE0" ++ s"_WIDTH {1} ")
        tclOutputFile.append(s"CONFIG.C_PROBE0" ++ s"_MU_CNT {3} ")
    }


    //vivado tcl epilogue
    val numprobes = if (mapping.size > 0) {mapping.size} else {1}
    val probetriggers = 3
    tclOutputFile.append(s"CONFIG.C_NUM_OF_PROBES {$numprobes} ")
    tclOutputFile.append(s"CONFIG.C_TRIGOUT_EN {false} ")
    tclOutputFile.append(s"CONFIG.C_EN_STRG_QUAL {1} ")
    tclOutputFile.append(s"CONFIG.C_ADV_TRIGGER {true} ")
    tclOutputFile.append(s"CONFIG.C_TRIGIN_EN {false} ")
    tclOutputFile.append(s"CONFIG.ALL_PROBE_SAME_MU_CNT {$probetriggers} ")
    tclOutputFile.append(s"] [get_ips ila_firesim_0]\n")
    tclOutputFile.append(s"generate_target {instantiation_template} [get_files $$CL_DIR/ip/firesim_ila_ip/ila_firesim_0/ila_firesim_0.xci]\n")
    tclOutputFile.append(s"generate_target all [get_files  $$CL_DIR/ip/firesim_ila_ip/ila_firesim_0/ila_firesim_0.xci]\n")
    tclOutputFile.append(s"export_ip_user_files -of_objects [get_files $$CL_DIR/ip/firesim_ila_ip/ila_0/ila_firesim_0.xci] -no_script -sync -force -quiet\n")
    tclOutputFile.append(s"create_ip_run [get_files -of_objects [get_fileset sources_1] $$CL_DIR/ip/firesim_ila_ip/ila_firesim_0/ila_firesim_0.xci]\n")
    tclOutputFile.append(s"launch_runs -jobs 8 ila_firesim_0_synth_1\n")
    tclOutputFile.append(s"wait_on_run ila_firesim_0_synth_1\n")
    tclOutputFile.append(s"export_simulation -of_objects [get_files $$CL_DIR/ip/firesim_ila_ip/ila_firesim_0/ila_firesim_0.xci] -directory $$CL_DIR/ip/firesim_ila_ip/ip_user_files/sim_scripts -ip_user_files_dir $$CL_DIR/ip/firesim_ila_ip/ip_user_files -ipstatic_source_dir $$CL_DIR/ip/firesim_ila_ip/ip_user_files/ipstatic -lib_map_path [list {modelsim=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/modelsim} {questa=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/questa} {ies=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/ies} {vcs=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/vcs} {riviera=$$CL_DIR/ip/firesim_ila_ip/managed_ip_project/managed_ip_project.cache/compile_simlib/riviera}] -use_ip_compiled_libs -force -quiet\n")

    if (mapping.nonEmpty) {
      //verilog ila instantiation `include epilogue
      ilaInstOutputFile.append(s"\n);\n")
    }

    tclOutputFile.close()
    portsOutputFile.close()
    wiresOutputFile.close()
    ilaInstOutputFile.close()

    state
  }

  def execute(state: CircuitState): CircuitState = {
    val ilaannos = state.annotations.collect {
      case a @ (_: FirrtlFpgaDebugAnnotation) => a
    }

    //Take debug annotation and make them into TopWiring annotations
    val targetannos = ilaannos match {
      case p => p.map { case FirrtlFpgaDebugAnnotation(target) => TopWiringAnnotation(target, s"ila_")  }
    }

    //dirname should be some aws-fpga synthesis directory
    val topwiringannos = targetannos ++ Seq(TopWiringOutputFilesAnnotation(dir.getPath(), ILAWiringOutputFiles))

    def topwiringtransform = new TopWiringTransform
    topwiringtransform.execute(state.copy(annotations = state.annotations ++ topwiringannos))
  }
}
