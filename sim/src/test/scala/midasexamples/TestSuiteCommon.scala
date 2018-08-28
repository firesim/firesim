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
