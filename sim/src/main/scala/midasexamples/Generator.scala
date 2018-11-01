//See LICENSE for license details.

package firesim.midasexamples

import midas._
import freechips.rocketchip.config.Config
import java.io.File

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
    case midas.F1       => new Config(new DefaultMIDASConfig ++ new midas.F1Config)
  }).toInstance

 lazy val hostTransforms = Seq(
    new firesim.passes.ILATopWiringTransform(genDir)
  )

  def compile() { MidasCompiler(dut, genDir, hostTransforms = hostTransforms)(midasParams) }
  def compileWithSnaptshotting() {
    MidasCompiler(dut, genDir, hostTransforms = hostTransforms)(
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
