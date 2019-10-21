//See LICENSE for license details.

package firesim.fasedtests

import chisel3.internal.firrtl.{Port}

import midas._
import freechips.rocketchip.config.Config
import freechips.rocketchip.diplomacy.{AutoBundle}
import java.io.{File, FileWriter}

import firesim.util.{GeneratorArgs, HasTargetAgnosticUtilites}

object Generator extends App with firesim.util.HasFireSimGeneratorUtilities {
  val longName = names.topModuleProject + "." + names.topModuleClass + "." + names.configs
  lazy val generatorArgs = GeneratorArgs(args)
  lazy val genDir = new File(names.targetDir)
  elaborate
}
