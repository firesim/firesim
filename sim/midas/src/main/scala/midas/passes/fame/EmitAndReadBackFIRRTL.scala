// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.Parser
import java.io.{File, PrintWriter}
import scala.sys.process._

class EmitAndReadBackFIRRTL(firFile: String, annoFile: String) extends Transform {
  import firrtl.options.TargetDirAnnotation
  def inputForm = UnknownForm
  def outputForm = UnknownForm

  def runExternalBinary(binaryPath: String, args: Seq[String]): Int = {
    val cmd = Process(binaryPath +: args)
    val exitCode = cmd.!
    exitCode
  }

  def execute(state: CircuitState): CircuitState = {
    val q  =
      state.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) => p }).get

    val targetDir  = state.annotations.collectFirst { case TargetDirAnnotation(dir) => dir }
    val dirName    = targetDir.getOrElse(".")

    // Usage
    val binary_path = "/scratch/fpga-demo/coding/ripple-ir/target/release/ripple-ir";
    val args = Seq(
        s"--input", s"${dirName}/${firFile}",
        "--output", s"${dirName}/post-custom-binary.fir",
        "--annos-in", s"${dirName}/${annoFile}",
        "--annos-out", s"${dirName}/post-custom-binary.json",
        "--firrtl-version", "firrtl3")

    println(s"args ${args}")

    val exit = runExternalBinary(binary_path, args)
    println(s"Binary exited with code $exit")

    // Read the circuit back
    val reader = scala.io.Source.fromFile(s"${dirName}/post-custom-binary.fir")
    val fileContents = try reader.mkString finally reader.close()
    val parsedCircuit = Parser.parse(fileContents)

    // Read annotations back
    val annoReader = scala.io.Source.fromFile(s"${dirName}/post-custom-binary.json")
    val annoFileContents = try annoReader.mkString finally annoReader.close()
    val parsedAnnos = JsonProtocol.deserialize(annoFileContents)
    val annos: AnnotationSeq = parsedAnnos ++ Seq(midas.stage.phases.ConfigParametersAnnotation(q))

    println(s"Read back circuit from $firFile and annotations from $annoFile")

    // Return the read-back circuit state
    CircuitState(parsedCircuit, annos)
  }
}
