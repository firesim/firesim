//See LICENSE for license details.
package firesim

import java.io.File
import scala.io.Source
import scala.sys.process.{stringSeqToProcess, ProcessLogger}
import freechips.rocketchip.config.Config

/**
  * A base class that captures the platform-specific parts of the configuration of a test.
  *
  * @param platformName Name of the target platform (f1 or vitis)
  * @param configs List of platform-specific configuration classes
  */
abstract class BasePlatformConfig(val platformName: String, val configs: Seq[Class[_ <: Config]])

/**
  * An base class for implementing FireSim integration tests that call out to the Make 
  * buildsystem. These tests typically have three steps whose results are tracked by scalatest:
  * 1) Elaborate the target and compile it through golden gate (ElaborateAndCompile)
  * 2) Compile a metasimulator for the generated RTL (compileMlSimulator)
  * 3) Run the metasimulator. Running a metasimualtion is somewaht
  *    target-specific and is handled differently by different subclasses.
  *
  * Some tests inspect the simulation outputs, or run other simulators. See the
  * [[TutorialSuite]] for examples of that.
  *
  * NB: Not thread-safe.
  */
abstract class TestSuiteBase extends org.scalatest.flatspec.AnyFlatSpec {

  def commonMakeArgs: Seq[String]
  def targetName: String
  def targetConfigs: String = "NoConfig"
  def platformMakeArgs: Seq[String] = Seq()

  val replayBackends = Seq("rtl")

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

abstract class TestSuiteCommon(targetProject: String) extends TestSuiteBase {
  def platformConfigs: Seq[Class[_ <: Config]] = Seq()
  def basePlatformConfig: BasePlatformConfig

  def platformConfigString = (platformConfigs ++ basePlatformConfig.configs).map(_.getSimpleName).mkString("_")

  override val platformMakeArgs = Seq(s"PLATFORM=${basePlatformConfig.platformName}")
  override val commonMakeArgs = Seq(s"TARGET_PROJECT=${targetProject}",
                           s"DESIGN=${targetName}",
                           s"TARGET_CONFIG=${targetConfigs}",
                           s"PLATFORM_CONFIG=${platformConfigString}")

  val targetTuple = s"${targetName}-${targetConfigs}-${platformConfigString}"

  // These mirror those in the make files; invocation of the MIDAS compiler
  // is the one stage of the tests we don't invoke the Makefile for
  lazy val genDir  = new File(firesimDir, s"generated-src/${basePlatformConfig.platformName}/${targetTuple}")
  lazy val outDir  = new File(firesimDir, s"output/${basePlatformConfig.platformName}/${targetTuple}")

  def mkdirs() { genDir.mkdirs; outDir.mkdirs }
}
