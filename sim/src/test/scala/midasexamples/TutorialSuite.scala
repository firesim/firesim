//See LICENSE for license details.
package firesim.midasexamples

import java.io.File
import scala.reflect.ClassTag
import scala.sys.process.{stringSeqToProcess, ProcessLogger}
import chisel3.Module

abstract class TestSuiteCommon extends org.scalatest.FlatSpec {

  def targetTuple: String
  def commonMakeArgs: Seq[String]
  def platform: midas.PlatformType

  val platformName = platform.toString.toLowerCase
  val replayBackends = Seq("rtl")

  // These mirror those in the make files; invocation of the MIDAS compiler
  // is the one stage of the tests we don't invoke the Makefile for
  lazy val genDir  = new File(s"generated-src/${platformName}/${targetTuple}")
  lazy val outDir = new File(s"output/${platformName}/${targetTuple}")

  implicit def toStr(f: File): String = f.toString replace (File.separator, "/")

  // Runs make passing default args to specify the right target design, project and platform
  def make(makeArgs: String*): Int = {
    val cmd = Seq("make") ++ makeArgs.toSeq ++ commonMakeArgs
    println("Running: %s".format(cmd mkString " "))
    cmd.!
  }

  implicit val p = (platform match {
    case midas.F1 => new midas.F1Config
    case midas.Zynq => new midas.ZynqConfig
  }).toInstance

  def clean() { make("clean") }
  def mkdirs() { genDir.mkdirs; outDir.mkdirs }

  def isCmdAvailable(cmd: String) =
    Seq("which", cmd) ! ProcessLogger(_ => {}) == 0

  // Compiles a MIDAS-level RTL simulator of the target
  def compileMlSimulator(b: String, debug: Boolean = false) {
    if (isCmdAvailable(b)) {
      assert(make(s"$b%s".format(if (debug) "-debug" else "")) == 0)
    }
  }

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

  //def runReplay(b: String, sample: Option[File] = None) = {
  //  if (isCmdAvailable("vcs")) {
  //    Seq("make", s"$replay-$b", s"PLATFORM=$platformName",
  //        "SAMPLE=%s".format(sample map (_.toString) getOrElse "")).!
  //  } else 0
  //}
}

abstract class TutorialSuite(
    val targetName: String, // See GeneratorUtils
    val platform: midas.PlatformType, // See TestSuiteCommon
    tracelen: Int = 8,
    simulationArgs: Seq[String] = Seq()
  ) extends TestSuiteCommon with GeneratorUtils {

  val args = Seq(s"+tracelen=$tracelen") ++ simulationArgs
  val commonMakeArgs = Seq(s"ROOT_PROJECT=midasexamples", s"DESIGN=$targetName", s"PLATFORM=$platformName")
  val targetTuple = targetName

  def runTest(b: String) {
    behavior of s"$targetName in $b"
    compileMlSimulator(b, true)
    val sample = Some(new File(outDir, s"$targetName.$b.sample"))
    if (isCmdAvailable(b)) {
      it should s"pass in MIDAS-level simulation" in {
        assert(run(b, true, sample, args=args) == 0)
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
      ignore should s"pass in MIDAS-level simulation" in { }
    }
  }
  clean
  mkdirs
  compile
  runTest("verilator")
  runTest("vcs")
}

//class PointerChaserF1Tests extends TutorialSuite("PointerChaser", midas.F1, 8, Seq("`cat runtime.conf`"))
//class GCDF1Test extends TutorialSuite("GCD", midas.F1, 3)
//class ParityF1Test extends TutorialSuite("Parity", midas.F1)
//class ShiftRegisterF1Test extends TutorialSuite("ShiftRegister", midas.F1)
//class ResetShiftRegisterF1Test extends TutorialSuite("ResetShiftRegister", midas.F1)
//class EnableShiftRegisterF1Test extends TutorialSuite("EnableShiftRegister", midas.F1)
//class StackF1Test extends TutorialSuite("Stack", midas.F1, 8)
//class RiscF1Test extends TutorialSuite("Risc", midas.F1, 64)
//class RiscSRAMF1Test extends TutorialSuite("RiscSRAM", midas.F1, 64)
