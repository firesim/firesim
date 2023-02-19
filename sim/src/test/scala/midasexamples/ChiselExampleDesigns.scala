// See LICENSE for license details.

package firesim.midasexamples

import java.io.File
import scala.io.Source
import org.scalatest.Suites

class GCDF1Test    extends TutorialSuite("GCD", basePlatformConfig = BaseConfigs.F1)
class GCDVitisTest extends TutorialSuite("GCD", basePlatformConfig = BaseConfigs.Vitis)

// Hijack Parity to test all of the Midas-level backends
class ParityF1Test    extends TutorialSuite("Parity", basePlatformConfig = BaseConfigs.F1)
class ParityVitisTest extends TutorialSuite("Parity", basePlatformConfig = BaseConfigs.Vitis)

class CustomConstraintsF1Test extends TutorialSuite("CustomConstraints") {

  override def defineTests(backend: String, debug: Boolean) {
    def readLines(filename: String): List[String] = {
      val file = new File(genDir, s"/${filename}")
      Source.fromFile(file).getLines.toList
    }
    it should s"generate synthesis XDC file" in {
      val xdc = readLines("FireSim-generated.synthesis.xdc")
      xdc should contain("constrain_synth1")
      (atLeast(1, xdc) should fullyMatch).regex("constrain_synth2 \\[reg firesim_top/.*/dut/r0\\]".r)
    }
    it should s"generate implementation XDC file" in {
      val xdc = readLines("FireSim-generated.implementation.xdc")
      xdc should contain("constrain_impl1")
      (atLeast(1, xdc) should fullyMatch).regex("constrain_impl2 \\[reg WRAPPER_INST/CL/firesim_top/.*/dut/r1]".r)
    }
  }
}

class TerminationF1Test extends TutorialSuite("TerminationModule") {
  override def defineTests(backend: String, debug: Boolean) {
    (1 to 10).foreach { x =>
      val args = Seq("+termination-bridge-tick-rate=10", s"+fuzz-seed=${x}")
      assert(run(backend, debug, args = args) == 0)
    }
  }
}

class TerminationAssertF1Test extends TutorialSuite("TerminationModuleAssert")

class ShiftRegisterF1Test       extends TutorialSuite("ShiftRegister")
class ResetShiftRegisterF1Test  extends TutorialSuite("ResetShiftRegister")
class EnableShiftRegisterF1Test extends TutorialSuite("EnableShiftRegister")
class StackF1Test               extends TutorialSuite("Stack")
class RiscF1Test                extends TutorialSuite("Risc")
class RiscSRAMF1Test            extends TutorialSuite("RiscSRAM")
class AccumulatorF1Test         extends TutorialSuite("Accumulator")
class VerilogAccumulatorF1Test  extends TutorialSuite("VerilogAccumulator")

// Midasexample Suite Collections
class ChiselExampleDesigns
    extends Suites(
      new GCDF1Test,
      new ParityF1Test,
      new PlusArgsGroup68Bit,
      new PlusArgsGroup29Bit,
      new ResetShiftRegisterF1Test,
      new EnableShiftRegisterF1Test,
      new StackF1Test,
      new RiscF1Test,
      new RiscSRAMF1Test,
      new AccumulatorF1Test,
      new VerilogAccumulatorF1Test,
      new CustomConstraintsF1Test,
      // This test is known to fail non-deterministically. See https://github.com/firesim/firesim/issues/1147
      // new TerminationF1Test
      new TerminationAssertF1Test,
    )
