// See LICENSE for license details.

package firesim.passes

import firesim.util.{DesiredHostFrequency, BuildStrategy}

import freechips.rocketchip.config.Parameters

import firrtl._
import firrtl.passes._
import firrtl.annotations._
import java.io.{File, FileWriter}

/** Generate additional output files for EC2 F1 host platforms, including TCL
  *  to configure a PLL with the desired frequency and to pick a desired
  *  build strategy
  */
class EC2F1Artefacts(implicit p: Parameters) extends Transform {
  def inputForm: CircuitForm = LowForm
  def outputForm: CircuitForm = LowForm
  override def name = "[FireSim] EC2 F1 Artefact Generation"

  def writeOutputFile(f: File, contents: String): File = {
    val fw = new FileWriter(f)
    fw.write(contents)
    fw.close
    f
  }

  // Capture FPGA-toolflow related verilog defines
  def generateHostVerilogHeader(targetDir: File) {
    val headerName = "cl_firesim_generated_defines.vh"
    writeOutputFile(new File(targetDir, headerName), s"\n")
  }

  // Emit TCL variables to control the FPGA compilation flow
  def generateTclEnvFile(targetDir: File)(implicit hostParams: Parameters) {
    val headerName = "cl_firesim_generated_env.tcl"
    val requestedFrequency = hostParams(DesiredHostFrequency)
    val buildStrategy      = hostParams(BuildStrategy)
    val constraints = s"""# FireSim Generated Environment Variables
set desired_host_frequency ${requestedFrequency}
${buildStrategy.emitTcl}
"""
    writeOutputFile(new File(targetDir, headerName), constraints)
  }

  def execute(state: CircuitState): CircuitState = {
    val targetDir = state.annotations.collectFirst({
      case TargetDirAnnotation(dir) => new File(dir)
    }).get
    generateHostVerilogHeader(targetDir)
    generateTclEnvFile(targetDir)
    state
  }
}
