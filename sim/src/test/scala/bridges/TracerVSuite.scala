//See LICENSE for license details.

package firesim.bridges

import java.io._

import org.scalatest.Suites
import org.scalatest.matchers.should._

import freechips.rocketchip.config.Config
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.config._

import firesim.{BasePlatformConfig, TestSuiteCommon}

abstract class TracerVTestBase(
  platformConfig: BasePlatformConfig,
  width:          Int,
  trace:          Option[Int] = None,
  start:          Option[String] = None,
) extends BridgeSuite("TracerVModule", s"TracerVModuleTestCount${width}", platformConfig) {
  override def runTest(backend: String, debug: Boolean) {
    // Create an expected file.
    val expected = File.createTempFile("expected", ".txt")
    // expected.deleteOnExit()

    // Create the output file. tracerv will always append '-C0' to the end of the path provided in the plusarg
    val output     = File.createTempFile("output", ".txt-C0")
    // output.deleteOnExit()
    val outputPath = output.getPath.substring(0, output.getPath.length() - 3)

    // group the optional function args together with the correct plusarg string names
    val optionalArgs = Seq(
      Seq("+trace-select=", trace),
      Seq("+trace-start=", start),
    )

    // a seq starting with fixed plusargs, ending with optional plusargs
    // the optional plusargs are properly constructed or dropped
    val args = Seq(
      s"+tracefile=${outputPath}",
      s"+tracerv-expected-output=${expected.getPath}",
    ) ++ optionalArgs.collect { case Seq(a, Some(b)) =>
      s"${a}${b}"
    }

    val runResult =
      run(backend, true, args = args)
    assert(runResult == 0)

    val expectedContents = scala.io.Source.fromFile(expected.getPath).mkString
    val outputContents   = scala.io.Source.fromFile(output.getPath).mkString
    outputContents should equal(expectedContents)
  }
}

class TracerVF1TestCount1 extends TracerVTestBase(BaseConfigs.F1, 1);
class TracerVF1TestCount6 extends TracerVTestBase(BaseConfigs.F1, 6, Some(2), Some("3000")); // in hex
class TracerVF1TestCount7 extends TracerVTestBase(BaseConfigs.F1, 7, Some(1), Some("9")); // in decimal // FIMXE test with 0xa
// class TracerVF1TestCount14 extends TracerVTestBase(BaseConfigs.F1, 14);
// class TracerVF1TestCount15 extends TracerVTestBase(BaseConfigs.F1, 15);
// class TracerVF1TestCount32 extends TracerVTestBase(BaseConfigs.F1, 32);
class TracerVVitisTest    extends TracerVTestBase(BaseConfigs.Vitis, 7);
