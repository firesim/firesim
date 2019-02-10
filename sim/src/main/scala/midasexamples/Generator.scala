//See LICENSE for license details.

package firesim.midasexamples

import midas._
import freechips.rocketchip.config.Config
import java.io.File

import firesim.util.{GeneratorArgs, HasTargetAgnosticUtilites}

trait GeneratorUtils extends HasTargetAgnosticUtilites {
  lazy val names = generatorArgs.targetNames
  lazy val targetParams = getParameters(names.fullConfigClasses)
  lazy val target = getGenerator(names, targetParams)
  lazy val hostNames = generatorArgs.platformNames
  lazy val hostParams = getHostParameters(names, hostNames)

  lazy val hostTransforms = Seq(
    new firesim.passes.ILATopWiringTransform(genDir)
  )

  def compile() { MidasCompiler(target, genDir, hostTransforms = hostTransforms)(hostParams) }
  def compileWithSnaptshotting() {
    MidasCompiler(target, genDir, hostTransforms = hostTransforms)(
      hostParams alterPartial { case midas.EnableSnapshot => true })
  }
}

object Generator extends App with GeneratorUtils {
  lazy val generatorArgs = GeneratorArgs(args)
  lazy val genDir = new File(names.targetDir)
  compile
  generateHostVerilogHeader
}
