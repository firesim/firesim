//See LICENSE for license details.
package firesim.midasexamples

import java.io.File
import scala.sys.process.{stringSeqToProcess, ProcessLogger}
import scala.io.Source

import firesim.util.GeneratorArgs

abstract class TutorialSuite(
    val targetName: String, // See GeneratorUtils
    targetConfigs: String = "NoConfig",
    platformConfigs: String = "HostDebugFeatures_DefaultF1Config",
    tracelen: Int = 8,
    simulationArgs: Seq[String] = Seq()
  ) extends firesim.TestSuiteCommon with firesim.util.HasFireSimGeneratorUtilities {

  val longName = names.topModuleProject + "." + names.topModuleClass + "." + names.configs
  val backendSimulator = "verilator"

  lazy val generatorArgs = GeneratorArgs(
    midasFlowKind = "midas",
    targetDir = "generated-src",
    topModuleProject = "firesim.midasexamples",
    topModuleClass = targetName,
    targetConfigProject = "firesim.midasexamples",
    targetConfigs = targetConfigs,
    platformConfigProject = "firesim.midasexamples",
    platformConfigs = platformConfigs)

  val args = Seq(s"+tracelen=$tracelen") ++ simulationArgs
  val commonMakeArgs = Seq(s"TARGET_PROJECT=midasexamples",
                           s"DESIGN=$targetName",
                           s"TARGET_CONFIG=${generatorArgs.targetConfigs}",
                           s"PLATFORM_CONFIG=${generatorArgs.platformConfigs}")
  val targetTuple = generatorArgs.tupleName

  def run(backend: String,
          debug: Boolean = false,
          sample: Option[File] = None,
          logFile: Option[File] = None,
          waveform: Option[File] = None,
          args: Seq[String] = Nil) = {
    val makeArgs = Seq(
      s"run-$backend%s".format(if (debug) "-debug" else ""),
      "SAMPLE=%s".format(sample map toStr getOrElse ""),
      "LOGFILE=%s".format(logFile map toStr getOrElse ""),
      "WAVEFORM=%s".format(waveform map toStr getOrElse ""),
      "ARGS=%s".format(args mkString " "))
    if (isCmdAvailable(backend)) {
      make(makeArgs:_*)
    } else 0
  }


  def runTest(b: String, debug: Boolean = false) {
    behavior of s"$targetName in $b"
    compileMlSimulator(b, debug)
    val sample = Some(new File(outDir, s"$targetName.$b.sample"))
    val testEnv = "MIDAS-level simulation" + { if (debug) " with waves enabled" else "" }
    if (isCmdAvailable(b)) {
      it should s"pass in ${testEnv}" in {
        assert(run(b, debug, sample, args=args) == 0)
      }
    } else {
      ignore should s"pass in ${testEnv}" in { }
    }
  }

  // Checks that a bridge generated log in ${genDir}/${synthLog} matches output
  // generated directly by the RTL simulator (usually with printfs)
  def diffSynthesizedLog(synthLog: String,
                         stdoutPrefix: String = "SYNTHESIZED_PRINT ",
                         synthPrefix: String  = "SYNTHESIZED_PRINT ") {
    behavior of s"${synthLog}"
    it should "match the prints generated the verilated design" in {
      def printLines(filename: File, prefix: String): Seq[String] = {
        val lines = Source.fromFile(filename).getLines.toList
        lines.filter(_.startsWith(prefix))
             .map(_.stripPrefix(prefix).replaceAll(" +", " "))
             .sorted
      }

      val verilatedOutput = printLines(new File(outDir,  s"/${targetName}.${backendSimulator}.out"), stdoutPrefix)
      val synthPrintOutput = printLines(new File(genDir, s"/${synthLog}"), synthPrefix)
      assert(verilatedOutput.size == synthPrintOutput.size && verilatedOutput.nonEmpty,
        s"\nSynthesized output had length ${synthPrintOutput.size}. Expected ${verilatedOutput.size}")
      for ( (vPrint, sPrint) <- verilatedOutput.zip(synthPrintOutput) ) {
        assert(vPrint == sPrint)
      }
    }
  }

  clean
  mkdirs
  elaborate
  runTest(backendSimulator)
}

//class PointerChaserF1Test extends TutorialSuite(
//  "PointerChaser", "PointerChaserConfig", simulationArgs = Seq("`cat runtime.conf`"))
class GCDF1Test extends TutorialSuite("GCD")
// Hijack Parity to test all of the Midas-level backends
class ParityF1Test extends TutorialSuite("Parity") {
  runTest("verilator", true)
  runTest("vcs", true)
}
class ShiftRegisterF1Test extends TutorialSuite("ShiftRegister")
class ResetShiftRegisterF1Test extends TutorialSuite("ResetShiftRegister")
class EnableShiftRegisterF1Test extends TutorialSuite("EnableShiftRegister")
class StackF1Test extends TutorialSuite("Stack")
class RiscF1Test extends TutorialSuite("Risc")
class RiscSRAMF1Test extends TutorialSuite("RiscSRAM")
class AssertModuleF1Test extends TutorialSuite("AssertModule")
class AutoCounterModuleF1Test extends TutorialSuite("AutoCounterModule",
    simulationArgs = Seq("+autocounter-readrate0=1000", "+autocounter-filename0=AUTOCOUNTERFILE0")) {
  diffSynthesizedLog("AUTOCOUNTERFILE0", "AUTOCOUNTER_PRINT ")
}
class AutoCounterCoverModuleF1Test extends TutorialSuite("AutoCounterCoverModule",
    simulationArgs = Seq("+autocounter-readrate0=1000", "+autocounter-filename0=AUTOCOUNTERFILE0")) {
  diffSynthesizedLog("AUTOCOUNTERFILE0", "AutoCounterCoverModule.autocounter.out")
}
class AutoCounterPrintfF1Test extends TutorialSuite("AutoCounterPrintfModule",
    simulationArgs = Seq("+print-file0=synthprinttest.out"),
    platformConfigs = "AutoCounterPrintf_HostDebugFeatures_DefaultF1Config") {
  diffSynthesizedLog("synthprinttest.out", synthPrefix = "")
}
class PrintfModuleF1Test extends TutorialSuite("PrintfModule",
  simulationArgs = Seq("+print-no-cycle-prefix", "+print-file0=synthprinttest.out")) {
  diffSynthesizedLog("synthprinttest.out")
}
class NarrowPrintfModuleF1Test extends TutorialSuite("NarrowPrintfModule",
  simulationArgs = Seq("+print-no-cycle-prefix", "+print-file0=synthprinttest.out")) {
  diffSynthesizedLog("synthprinttest.out")
}

class WireInterconnectF1Test extends TutorialSuite("WireInterconnect")
class TrivialMulticlockF1Test extends TutorialSuite("TrivialMulticlock") {
  runTest("verilator", true)
  runTest("vcs", true)
}

class MulticlockAssertF1Test extends TutorialSuite("MultiClockAssertModule")

class MulticlockPrintF1Test extends TutorialSuite("MulticlockPrintfModule",
  simulationArgs = Seq("+print-file0=synthprinttest0.out",
                       "+print-file1=synthprinttest1.out",
                       "+print-no-cycle-prefix")) {
  diffSynthesizedLog("synthprinttest0.out")
  diffSynthesizedLog("synthprinttest1.out",
    stdoutPrefix = "SYNTHESIZED_PRINT_HALFRATE ",
    synthPrefix = "SYNTHESIZED_PRINT_HALFRATE ")
}

// Basic test for deduplicated extracted models
class TwoAddersF1Test extends TutorialSuite("TwoAdders")
