//See LICENSE for license details.
package firesim

import java.io.File
import scala.sys.process.{stringSeqToProcess, ProcessLogger}

/**
  * NB: not thread-safe
  */
abstract class TestSuiteCommon extends org.scalatest.flatspec.AnyFlatSpec {

  def targetTuple: String
  def commonMakeArgs: Seq[String]

  val platformName = "f1"
  val replayBackends = Seq("rtl")
  val platformMakeArgs = Seq(s"PLATFORM=$platformName")

  // Check if we are running out of Chipyard by checking for the existence of a firesim/sim directory
  val firesimDir = {
    val cwd = System.getProperty("user.dir")
    val firesimAsLibDir = new File(cwd, "sims/firesim/sim")
    if (firesimAsLibDir.exists()) {
      firesimAsLibDir
    } else {
      new File(cwd)
    }
  }

  var ciSkipElaboration: Boolean = false
  var transitiveFailure: Boolean = false

	override def withFixture(test: NoArgTest) = {
		// Perform setup
    ciSkipElaboration = test.configMap.getOptional[String]("ci-skip-elaboration")
      .map { _.toBoolean }
      .getOrElse(false)
    if (transitiveFailure) {
      org.scalatest.Canceled("Due to prior failure")
    } else {
      super.withFixture(test)
    }
	}

  // These mirror those in the make files; invocation of the MIDAS compiler
  // is the one stage of the tests we don't invoke the Makefile for
  lazy val genDir  = new File(firesimDir, s"generated-src/${platformName}/${targetTuple}")
  lazy val outDir  = new File(firesimDir, s"output/${platformName}/${targetTuple}")

  implicit def toStr(f: File): String = f.toString replace (File.separator, "/")

  // Defines a make target that will build all prerequistes for downstream
  // tests that require a Scala invocation.
  def elaborateMakeTarget: Seq[String] = Seq("compile")

  def makeCommand(makeArgs: String*): Seq[String] = {
    Seq("make", "-C", s"$firesimDir") ++ makeArgs.toSeq ++ commonMakeArgs ++ platformMakeArgs
  }

  // Runs make passing default args to specify the right target design, project and platform
  def make(makeArgs: String*): Int = {
    val cmd = makeCommand(makeArgs:_*)
    println("Running: %s".format(cmd mkString " "))
    cmd.!
  }

  // As above, but if the RC is non-zero, cancels all downstream tests.  This
  // is used to prevent re-invoking make on a target with a dependency on the
  // result of this recipe. Which would lead to a second failure.
  def makeCriticalDependency(makeArgs: String*): Int = {
    val returnCode = make(makeArgs:_*)
    transitiveFailure = returnCode != 0
    returnCode
  }

  def clean() { make("clean") }
  def mkdirs() { genDir.mkdirs; outDir.mkdirs }

  def isCmdAvailable(cmd: String) =
    Seq("which", cmd) ! ProcessLogger(_ => {}) == 0

  // Running all scala-invocations required to take the design to verilog.
  // Generally elaboration + GG compilation.
  def elaborateAndCompile(behaviorDescription: String = "elaborate and compile through GG sucessfully") {
    it should behaviorDescription in {
      // Under CI, if make failed during elaboration we catch it here without
      // attempting to rebuild
      val target = (if (ciSkipElaboration) Seq("-q") else Seq()) ++ elaborateMakeTarget
      assert(makeCriticalDependency(target:_*) == 0)
    }
  }

  // Compiles a MIDAS-level RTL simulator of the target
  def compileMlSimulator(b: String, debug: Boolean = false) {
    if (isCmdAvailable(b)) {
      it should s"compile sucessfully to ${b}" + { if (debug) " with waves enabled" else "" } in {
        assert(makeCriticalDependency(s"$b%s".format(if (debug) "-debug" else "")) == 0)
      }
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

  // Use the default recipe which also will compile verilator since there's no
  // separate target for just elaboration
  override def elaborateMakeTarget = Seq("compile-midas-unittests")
  override lazy val genDir  = new File(s"generated-src/unittests/${targetTuple}")
  override lazy val outDir = new File(s"output/unittests/${targetTuple}")

  def runUnitTestSuite(backend: String, debug: Boolean = false) {
    val testSpecString = if (shouldFail) "fail" else "pass" + s" when running under ${backend}"

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

  mkdirs
  behavior of s"MIDAS unittest: ${unitTestConfig}"
  elaborateAndCompile("elaborate sucessfully")
  runUnitTestSuite("verilator")
}

class AllMidasUnitTests extends MidasUnitTestSuite("AllUnitTests") {
  runUnitTestSuite("vcs")
}
// Need to get VCS to return non-zero exitcodes when $fatal is called
class FailingUnitTests extends MidasUnitTestSuite("TimeOutCheck", shouldFail = true)
