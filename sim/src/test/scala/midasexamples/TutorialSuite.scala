//See LICENSE for license details.
package firesim.midasexamples

import java.io.File
import scala.sys.process.{stringSeqToProcess, ProcessLogger}

abstract class TutorialSuite(
    val targetName: String, // See GeneratorUtils
    val platform: midas.PlatformType, // See TestSuiteCommon
    tracelen: Int = 8,
    simulationArgs: Seq[String] = Seq()
  ) extends TestSuiteCommon with GeneratorUtils {

  val args = Seq(s"+tracelen=$tracelen") ++ simulationArgs
  val commonMakeArgs = Seq(s"TARGET_PROJECT=midasexamples", s"DESIGN=$targetName")
  val targetTuple = targetName

  //implicit val p = (platform match {
  //  case midas.F1 => new midas.F1Config
  //  case midas.Zynq => new midas.ZynqConfig
  //}).toInstance

  //def runReplay(b: String, sample: Option[File] = None) = {
  //  if (isCmdAvailable("vcs")) {
  //    Seq("make", s"$replay-$b", s"PLATFORM=$platformName",
  //        "SAMPLE=%s".format(sample map (_.toString) getOrElse "")).!
  //  } else 0
  //}

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
      //if (p(midas.EnableSnapshot)) {
      //  replayBackends foreach { replayBackend =>
      //    if (isCmdAvailable("vcs")) {
      //      it should s"replay samples with $replayBackend" in {
      //        assert(runReplay(replayBackend, sample) == 0)
      //      }
      //    } else {
      //      ignore should s"replay samples with $replayBackend" in { }
      //    }
      //  }
      //}
    } else {
      ignore should s"pass in ${testEnv}" in { }
    }
  }
  clean
  mkdirs
  compile
  runTest("verilator")
  runTest("vcs", true)
}

class PointerChaserF1Test extends TutorialSuite("PointerChaser", midas.F1, 8, Seq("`cat runtime.conf`"))
class GCDF1Test extends TutorialSuite("GCD", midas.F1, 3)
// Hijack Parity to test all of the Midas-level backends
class ParityF1Test extends TutorialSuite("Parity", midas.F1) {
  runTest("verilator", true)
  runTest("vcs")
}
class ShiftRegisterF1Test extends TutorialSuite("ShiftRegister", midas.F1)
class ResetShiftRegisterF1Test extends TutorialSuite("ResetShiftRegister", midas.F1)
class EnableShiftRegisterF1Test extends TutorialSuite("EnableShiftRegister", midas.F1)
class StackF1Test extends TutorialSuite("Stack", midas.F1, 8)
class RiscF1Test extends TutorialSuite("Risc", midas.F1, 64)
class RiscSRAMF1Test extends TutorialSuite("RiscSRAM", midas.F1, 64)
class AssertModuleF1Test extends TutorialSuite("AssertModule", midas.F1)
