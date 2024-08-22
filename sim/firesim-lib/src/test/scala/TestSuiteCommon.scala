//See LICENSE for license details.
package firesim

import java.io.File
import scala.sys.process.{stringSeqToProcess, ProcessLogger}
import scala.collection.mutable

/** A base class that captures the platform-specific parts of the configuration of a test.
  *
  * @param platformName
  *   Name of the target platform (f1 or vitis)
  * @param configs
  *   List of platform-specific configuration classes
  */
abstract class BasePlatformConfig(val platformName: String, val configs: Seq[String])

/** An base class for implementing FireSim integration tests that call out to the Make buildsystem. These tests
  * typically have three steps whose results are tracked by scalatest: 1) Elaborate the target and compile it through
  * golden gate (ElaborateAndCompile) 2) Compile a metasimulator for the generated RTL (compileMlSimulator) 3) Run the
  * metasimulator. Running a metasimualtion is somewaht target-specific and is handled differently by different
  * subclasses.
  *
  * Some tests inspect the simulation outputs, or run other simulators. See the [[TutorialSuite]] for examples of that.
  *
  * NB: Not thread-safe.
  */
abstract class TestSuiteBase extends org.scalatest.flatspec.AnyFlatSpec {

  def commonMakeArgs: Seq[String]
  def targetName:     String
  def targetConfigs: String         = "NoConfig"
  def platformMakeArgs: Seq[String] = Seq()
  def extraMakeArgs: Seq[String]    = Seq()

  // since this test suite is used in Chipyard and FireSim you want to resolve the
  // FireSim path based on something other than the FIRESIM_STANDALONE env. var.
  val firesimDir = {
    // can either be in <firesim>/sim or in <chipyard>/
    val curDir = new File(System.getProperty("user.dir"))

    // determine if in a firesim or chipyard area
    val chipyardReadme = new File(curDir, "README.md") // HACK: chipyard README.md is in same dir as build.sbt

    val filetypeDir = if (chipyardReadme.exists()) {
      new File(curDir, "sims/firesim/sim")
    } else {
      curDir
    }

    // convert to path to use toRealPath (which does resolve symlinks unlike File.toAbsolutePath)
    val pathdir = filetypeDir.toPath().toRealPath()
    // convert back to File to keep same API
    pathdir.toFile()
  }

  var ciSkipElaboration: Boolean = false
  var transitiveFailure: Boolean = false

  override def withFixture(test: NoArgTest) = {
    // Perform setup
    ciSkipElaboration = test.configMap
      .getOptional[String]("ci-skip-elaboration")
      .map { _.toBoolean }
      .getOrElse(false)
    if (transitiveFailure) {
      org.scalatest.Canceled("Due to prior failure")
    } else {
      super.withFixture(test)
    }
  }

  implicit def toStr(f: File): String = f.toString.replace(File.separator, "/")

  // Defines a make target that will build all prerequistes for downstream
  // tests that require a Scala invocation.
  def elaborateMakeTarget: Seq[String] = Seq("compile")

  def makeCommand(makeArgs: String*): Seq[String] = {
    Seq("make", "-C", s"$firesimDir") ++ makeArgs.toSeq ++ commonMakeArgs ++ platformMakeArgs ++ extraMakeArgs
  }

  // Runs make passing default args to specify the right target design, project and platform
  def make(makeArgs: String*): Int = {
    val cmd = makeCommand(makeArgs: _*)
    println("Running: %s".format(cmd.mkString(" ")))
    cmd.!
  }

  // As above, but if the RC is non-zero, cancels all downstream tests.  This
  // is used to prevent re-invoking make on a target with a dependency on the
  // result of this recipe. Which would lead to a second failure.
  def makeCriticalDependency(makeArgs: String*): Int = {
    val returnCode = make(makeArgs: _*)
    transitiveFailure = returnCode != 0
    returnCode
  }

  def clean(): Unit = { make("clean") }

  def isCmdAvailable(cmd: String) =
    Seq("which", cmd) ! ProcessLogger(_ => {}) == 0

  // Running all scala-invocations required to take the design to verilog.
  // Generally elaboration + GG compilation.
  def elaborateAndCompile(behaviorDescription: String = "elaborate and compile through GG sucessfully"): Unit = {
    it should behaviorDescription in {
      // Under CI, if make failed during elaboration we catch it here without
      // attempting to rebuild
      val target = (if (ciSkipElaboration) Seq("-q") else Seq()) ++ elaborateMakeTarget
      assert(makeCriticalDependency(target: _*) == 0)
    }
  }
}

abstract class TestSuiteCommon(targetProject: String) extends TestSuiteBase {
  def platformConfigs: Seq[String] = Seq()
  def basePlatformConfig: BasePlatformConfig

  def run(
    backend:  String,
    debug:    Boolean      = false,
    logFile:  Option[File] = None,
    waveform: Option[File] = None,
    args:     Seq[String]  = Nil,
  ) = {
    val makeArgs = Seq(
      s"run-$backend%s".format(if (debug) "-debug" else ""),
      "LOGFILE=%s".format(logFile.map(toStr).getOrElse("")),
      "WAVEFORM=%s".format(waveform.map(toStr).getOrElse("")),
      "ARGS=%s".format(args.mkString(" ")),
    )
    if (isCmdAvailable(backend)) {
      make(makeArgs: _*)
    } else 0
  }

  def platformConfigString = (platformConfigs ++ basePlatformConfig.configs).mkString("_")

  override val platformMakeArgs = Seq(s"PLATFORM=${basePlatformConfig.platformName}")
  override val commonMakeArgs   = Seq(
    s"TARGET_PROJECT=${targetProject}",
    s"DESIGN=${targetName}",
    s"TARGET_CONFIG=${targetConfigs}",
    s"PLATFORM_CONFIG=${platformConfigString}",
  )

  val targetTuple =
    s"${basePlatformConfig.platformName}-${targetProject}-${targetName}-${targetConfigs}-${platformConfigString}"

  // These mirror those in the make files; invocation of the MIDAS compiler
  // is the one stage of the tests we don't invoke the Makefile for
  lazy val genDir = new File(firesimDir, s"generated-src/${basePlatformConfig.platformName}/${targetTuple}")
  lazy val outDir = new File(firesimDir, s"output/${basePlatformConfig.platformName}/${targetTuple}")

  def mkdirs(): Unit = { genDir.mkdirs; outDir.mkdirs }

  // Compiles a MIDAS-level RTL simulator of the target
  def compileMlSimulator(b: String, debug: Boolean): Unit = {
    it should s"compile sucessfully to ${b}" + { if (debug) " with waves enabled" else "" } in {
      assert(makeCriticalDependency(s"$b%s".format(if (debug) "-debug" else "")) == 0)
    }
  }

  /** Method to be implemented by tests, providing an invocation to a simulation run and checks.
    */
  def defineTests(backend: String, debug: Boolean): Unit

  // Overrideable method to specify test configurations.
  def simulators: Seq[String] = {
    val buffer = mutable.ArrayBuffer[String]()

    if (isCmdAvailable("verilator")) {
      if (System.getenv("TEST_DISABLE_VERILATOR") == null) {
        buffer += "verilator"
      }
    }

    if (isCmdAvailable("vcs")) {
      if (System.getenv("TEST_DISABLE_VCS") == null) {
        buffer += "vcs"
      }

      if (isCmdAvailable("vivado")) {
        if (System.getenv("TEST_DISABLE_VIVADO") == null) {
          buffer += "vcs-post-synth"
        }
      }
    }

    buffer.toSeq
  }

  def debugFlags: Seq[Boolean] = Seq(false)

  // Define test rules across the matrix of simulators and debug flags.
  mkdirs()
  for (simulator <- simulators) {
    for (debugFlag <- debugFlags) {
      behavior.of(s"$targetName with ${simulator}${if (debugFlag) "-debug" else ""}")
      elaborateAndCompile()
      compileMlSimulator(simulator, debugFlag)
      defineTests(simulator, debugFlag)
    }
  }
}
