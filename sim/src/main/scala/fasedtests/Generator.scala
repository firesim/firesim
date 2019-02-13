//See LICENSE for license details.

package firesim.fasedtests

import chisel3.internal.firrtl.{Port}

import midas._
import freechips.rocketchip.config.Config
import freechips.rocketchip.diplomacy.{AutoBundle}
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

  def elaborateAndCompileWithMidas() {
    val c3circuit = chisel3.Driver.elaborate(() => target)
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(c3circuit))
    val annos = c3circuit.annotations.map(_.toFirrtl)

    val portList = target.getPorts flatMap {
      case Port(id: AutoBundle, _) => None
      case otherPort => Some(otherPort.id.instanceName -> otherPort.id)
    }

    generatorArgs.midasFlowKind match {
      case "midas" | "strober" =>
        midas.MidasCompiler(
          chirrtl, annos, portList, genDir, None, Seq(), hostTransforms
        )(hostParams alterPartial {case midas.EnableSnapshot => generatorArgs.midasFlowKind == "strober" })
    }
  }
}

object Generator extends App with GeneratorUtils {
  lazy val generatorArgs = GeneratorArgs(args)
  lazy val genDir = new File(names.targetDir)
  elaborateAndCompileWithMidas
  generateHostVerilogHeader
}
