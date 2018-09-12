//See LICENSE for license details.
package firesim.midasexamples

import java.io.File
import freechips.rocketchip.config.Config
import midas._

trait GeneratorUtils {
  def targetName: String
  def genDir: File
  def platform: midas.PlatformType

  def dut = targetName match {
    case "PointerChaser" =>
      new PointerChaser()((new PointerChaserConfig).toInstance)
    case _ =>
      Class.forName(s"firesim.midasexamples.${targetName}")
           .getConstructors.head
           .newInstance()
           .asInstanceOf[chisel3.Module]
  }
  def midasParams = (platform match {
    case midas.F1       => new Config(new firesim.firesim.WithDefaultMemModel ++ new midas.F1Config)
  }).toInstance

  def compile() { MidasCompiler(dut, genDir)(midasParams) }
  def compileWithSnaptshotting() {
    MidasCompiler(dut, genDir)(
      midasParams alterPartial { case midas.EnableSnapshot => true })
  }
  def compileWithReplay() {
    strober.replay.Compiler(dut, genDir)
  }
}


object Generator extends App with GeneratorUtils {
  lazy val targetName = args(1)
  lazy val genDir = new File(args(2))
  lazy val platform = args(3) match {
    case "f1" => midas.F1
    case x => throw new RuntimeException(s"${x} platform is not supported in FireSim")
  }
  compile
}
