//See LICENSE for license details.

package firesim.midasexamples

import java.io.File
import freechips.rocketchip.config.Config
import midas._

object Generator extends App {
  lazy val modName = args(1)
  lazy val dirPath = args(2)
  lazy val platform = args(3)
  def dut = modName match {
    case "PointerChaser" =>
      new PointerChaser()((new PointerChaserConfig).toInstance)
    case _ =>
      Class.forName(s"firesim.midasexamples.${modName}")
           .getConstructors.head
           .newInstance()
           .asInstanceOf[chisel3.Module]
  }
  def midasParams = (platform match {
    case "f1"       => new Config(new firesim.firesim.WithDefaultMemModel ++ new midas.F1Config)
  }).toInstance
  args.head match {
    case "midas" =>
      MidasCompiler(dut, new File(dirPath))(midasParams)
    case "strober" =>
      val lib = if (args.size > 4) Some(new File(args(4))) else None
      MidasCompiler(dut, new File(dirPath), lib)(
        midasParams alterPartial { case midas.EnableSnapshot => true })
    case "replay" =>
      val lib = if (args.size > 3) Some(new File(args(3))) else None
      strober.replay.Compiler(dut, new File(dirPath), lib)
  }
}
