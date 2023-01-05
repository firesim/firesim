// See LICENSE for license details.

package firesim

import java.io.File

/** Hijacks TestSuiteBase (mostly for make related features) to run the synthesizable unit tests.
  */
abstract class MidasUnitTestSuite(
  override val targetName: String,
  shouldFail:              Boolean = false,
) extends TestSuiteBase {

  // GENERATED_DIR & OUTPUT_DIR are only used to properly invoke `make clean`
  val commonMakeArgs = Seq(s"UNITTEST_CONFIG=${targetName}", s"GENERATED_DIR=${genDir}", s"OUTPUT_DIR=${outDir}")

  // Use the default recipe which also will compile verilator since there's no
  // separate target for just elaboration
  override def elaborateMakeTarget = Seq("compile-midas-unittests")

  lazy val genDir = new File(s"generated-src/unittests/${targetName}")
  lazy val outDir = new File(s"output/unittests/${targetName}")

  def runUnitTestSuite(backend: String, debug: Boolean = false) {
    val testSpecString = if (shouldFail) "fail" else "pass" + s" when running under ${backend}"

    if (isCmdAvailable(backend)) {
      lazy val result = make("run-midas-unittests%s".format(if (debug) "-debug" else ""), s"EMUL=$backend")
      it should testSpecString in {
        if (shouldFail) assert(result != 0) else assert(result == 0)
      }
    } else {
      ignore should testSpecString in {}
    }
  }

  genDir.mkdirs
  outDir.mkdirs

  behavior.of(s"MIDAS unittest: ${targetName}")
  elaborateAndCompile("elaborate sucessfully")
  runUnitTestSuite("verilator")
}

class AllMidasUnitTests extends MidasUnitTestSuite("AllUnitTests") {
  runUnitTestSuite("vcs")
}

// Need to get VCS to return non-zero exitcodes when $fatal is called
class FailingUnitTests extends MidasUnitTestSuite("TimeOutCheck", shouldFail = true)
