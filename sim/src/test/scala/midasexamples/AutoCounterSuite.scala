// See LICENSE for license details.

package firesim.midasexamples

import java.io.File
import org.chipsalliance.cde.config.Config
import org.scalatest.Suites
import firesim.TestSuiteUtil._

import firesim.BasePlatformConfig

abstract class AutoCounterSuite(
  targetName:         String,
  checks:             Seq[(String, String)],
  targetConfigs:      String                  = "NoConfig",
  platformConfigs:    Seq[Class[_ <: Config]] = Seq(),
  basePlatformConfig: BasePlatformConfig      = BaseConfigs.F1,
  simulationArgs:     Seq[String]             = Seq(),
) extends TutorialSuite(targetName, targetConfigs, platformConfigs, basePlatformConfig, simulationArgs) {

  /** Compares an AutoCounter output CSV against a reference generated using in-circuit printfs.
    */
  def checkAutoCounterCSV(backend: String, filename: String, stdoutPrefix: String) {
    it should s"produce a csv file (${filename}) that matches in-circuit printf output" in {
      val scrubWhitespace = raw"\s*(.*)\s*".r
      def splitAtCommas(s: String) = {
        s.split(",")
          .map(scrubWhitespace.findFirstMatchIn(_).get.group(1))
      }

      def quotedSplitAtCommas(s: String) = {
        s.split("\",\"")
          .map(scrubWhitespace.findFirstMatchIn(_).get.group(1))
      }

      val refLogFile = new File(outDir, s"/${targetName}.${backend}.out")
      val acFile     = new File(genDir, s"/${filename}")

      val refVersion :: refClockInfo :: refLabelLine :: refDescLine :: refOutput =
        extractLines(refLogFile, stdoutPrefix, headerLines = 0).toList
      val acVersion :: acClockInfo :: acLabelLine :: acDescLine :: acOutput      =
        extractLines(acFile, prefix = "", headerLines = 0).toList

      assert(acVersion == refVersion)

      val refLabels = splitAtCommas(refLabelLine)
      val acLabels  = splitAtCommas(acLabelLine)
      acLabels should contain theSameElementsAs refLabels

      val swizzle: Seq[Int] = refLabels.map { acLabels.indexOf(_) }

      def checkLine(acLine: String, refLine: String, tokenizer: String => Seq[String] = splitAtCommas) {
        val Seq(acFields, refFields) = Seq(acLine, refLine).map(tokenizer)
        val assertMessagePrefix      = s"Row commencing with ${refFields.head}:"
        assert(acFields.size == refFields.size, s"${assertMessagePrefix} lengths do not match")
        for ((field, columnIdx) <- refFields.zipWithIndex) {
          assert(
            field == acFields(swizzle(columnIdx)),
            s"${assertMessagePrefix} value for label ${refLabels(columnIdx)} does not match.",
          )
        }
      }

      for ((acLine, refLine) <- acOutput.zip(refOutput)) {
        checkLine(acLine, refLine)
      }
    }
  }

  override def defineTests(backend: String, debug: Boolean) {
    it should "run in the simulator" in {
      assert(run(backend, debug, args = simulationArgs) == 0)
    }
    for ((filename, prefix) <- checks) {
      checkAutoCounterCSV(backend, filename, prefix)
    }
  }
}

class AutoCounterModuleF1Test
    extends AutoCounterSuite(
      "AutoCounterModule",
      Seq(("autocounter0.csv", "AUTOCOUNTER_PRINT ")),
      simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter"),
    )

class AutoCounter32bRolloverTest
    extends AutoCounterSuite(
      "AutoCounter32bRollover",
      Seq(("autocounter0.csv", "AUTOCOUNTER_PRINT ")),
      simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter"),
    )

class AutoCounterCoverModuleF1Test
    extends AutoCounterSuite(
      "AutoCounterCoverModule",
      Seq(("autocounter0.csv", "AUTOCOUNTER_PRINT ")),
      simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter"),
    )

class MulticlockAutoCounterF1Test
    extends AutoCounterSuite(
      "MulticlockAutoCounterModule",
      Seq(
        ("autocounter0.csv", "AUTOCOUNTER_PRINT "),
        ("autocounter1.csv", "AUTOCOUNTER_PRINT_SLOWCLOCK "),
      ),
      simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter"),
    )

class AutoCounterGlobalResetConditionF1Test
    extends TutorialSuite(
      "AutoCounterGlobalResetCondition",
      simulationArgs = Seq("+autocounter-readrate=1000", "+autocounter-filename-base=autocounter"),
    ) {

  def assertCountsAreZero(filename: String, clockDivision: Int, backend: String) {
    s"Counts reported in ${filename} in ${backend}" should "always be zero" in {
      val log                  = new File(genDir, s"/${filename}")
      val versionLine :: lines = extractLines(log, "", headerLines = 0).toList
      val sampleLines          = lines.drop(AutoCounterVerificationConstants.headerLines - 1)

      assert(versionLine.split(",")(1).toInt == AutoCounterVerificationConstants.expectedCSVVersion)

      val perfCounterRegex = raw"(\d*),(\d*),(\d*)".r
      sampleLines.zipWithIndex.foreach { case (perfCounterRegex(baseCycle, localCycle, value), idx) =>
        assert(baseCycle.toInt == 1000 * (idx + 1))
        assert(localCycle.toInt == (1000 / clockDivision) * (idx + 1))
        assert(value.toInt == 0)
      }
    }
  }

  override def defineTests(backend: String, debug: Boolean) {
    it should "run in the simulator" in {
      assert(run(backend, debug, args = simulationArgs) == 0)
    }
    assertCountsAreZero("autocounter0.csv", clockDivision = 1, backend)
    assertCountsAreZero("autocounter1.csv", clockDivision = 2, backend)
  }
}

class AutoCounterCITests
    extends Suites(
      new AutoCounterModuleF1Test,
      new AutoCounterCoverModuleF1Test,
      new MulticlockAutoCounterF1Test,
      new AutoCounterGlobalResetConditionF1Test,
      new AutoCounter32bRolloverTest,
    )
