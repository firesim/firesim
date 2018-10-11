//See LICENSE for license details.
package firesim.midasexamples

import java.io.File
import scala.sys.process.{stringSeqToProcess, ProcessLogger}

abstract class TestSuiteCommon extends org.scalatest.FlatSpec {

  def targetTuple: String
  def commonMakeArgs: Seq[String]
  def platform: midas.PlatformType

  val platformName = platform.toString.toLowerCase
  val replayBackends = Seq("rtl")
  val platformMakeArgs = Seq(s"PLATFORM=$platformName")

  // These mirror those in the make files; invocation of the MIDAS compiler
  // is the one stage of the tests we don't invoke the Makefile for
  lazy val genDir  = new File(s"generated-src/${platformName}/${targetTuple}")
  lazy val outDir = new File(s"output/${platformName}/${targetTuple}")

  implicit def toStr(f: File): String = f.toString replace (File.separator, "/")

  // Runs make passing default args to specify the right target design, project and platform
  def make(makeArgs: String*): Int = {
    val cmd = Seq("make") ++ makeArgs.toSeq ++ commonMakeArgs ++ platformMakeArgs
    println("Running: %s".format(cmd mkString " "))
    cmd.!
  }

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
}

// HACK: Hijacks TestSuiteCommon to run the MIDAS unit tests
abstract class MidasUnitTestSuite(unitTestConfig: String, shouldFail: Boolean = false) extends TestSuiteCommon {
  val targetTuple = unitTestConfig
  // GENERATED_DIR & OUTPUT_DIR are only used to properly invoke `make clean`
  val commonMakeArgs = Seq(s"UNITTEST_CONFIG=${unitTestConfig}",
                           s"GENERATED_DIR=${genDir}",
                           s"OUTPUT_DIR=${outDir}")
  // Currently, this is just a dummy arg
  lazy val platform = midas.F1

  override lazy val genDir  = new File(s"generated-src/unittests/${targetTuple}")
  override lazy val outDir = new File(s"output/unittests/${targetTuple}")

  def runUnitTestSuite(backend: String, debug: Boolean = false) {
    behavior of s"MIDAS unittest: ${unitTestConfig} running on ${backend}"
    val testSpecString = if (shouldFail) "fail" else "pass"

    if (isCmdAvailable(backend)) {
      lazy val result = make("run-midas-unittests%s".format(if (debug) "-debug" else ""),
                             s"EMUL=$backend")
      it should testSpecString in {
        if (shouldFail) assert(result != 0) else assert(result == 0)
      }
    } else {
      ignore should testSpecString in { }
    }
  }

  clean
  mkdirs
  runUnitTestSuite("verilator")
}

class AllMidasUnitTests extends MidasUnitTestSuite("AllUnitTests") {
  runUnitTestSuite("vcs")
}
// Need to get VCS to return non-zero exitcodes when $fatal is called
class FailingUnitTests extends MidasUnitTestSuite("TimeOutCheck", shouldFail = true)
