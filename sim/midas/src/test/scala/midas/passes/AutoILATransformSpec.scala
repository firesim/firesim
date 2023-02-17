// See LICENSE for license details.

package midas.tests

import logger._
import midas.EnableAutoILA
import midas.targetutils.{FirrtlFpgaDebugAnnotation, FpgaDebug}
import midas.passes._
import midas.stage.phases.ConfigParametersAnnotation

import freechips.rocketchip.config.{Config, Parameters}

import firrtl.annotations._
import firrtl.transforms.BlackBoxInlineAnno
import firrtl.testutils._
import midas.stage.OutputBaseFilenameAnnotation
import midas.stage.GoldenGateOutputFileAnnotation

class BaseAutoILAConfig extends Config((site, here, up) => { case EnableAutoILA => true })

class AutoILATransformSpec extends MiddleTransformSpec with FirrtlRunners {

  def transform = AutoILATransform

  def topMT                = ModuleTarget("Top", "Top")
  def clockSourceAnno      = HostClockSource(ModuleTarget("Top", "Top").ref("clock"))
  def configParametersAnno = ConfigParametersAnnotation((new BaseAutoILAConfig).toInstance)
  def baseFileNameAnno     = OutputBaseFilenameAnnotation("FireSim-generated")
  val baseAnnos            = Seq(configParametersAnno, clockSourceAnno, baseFileNameAnno)

  behavior.of("AutoILATransform")

  {
    val input =
      """|circuit Top :
         |  module Top :
         |    input clock : Clock
         |    input a : UInt<1>
         |    wire b : UInt<8>
         |    b <= UInt<8>(0)
         |    inst mod of Mod
         |  module Mod:
         |   output a : UInt<1>
         |   a <= UInt<1>(0)
         |""".stripMargin

    val output =
      """|circuit Top :
         |  module Top :
         |    input clock : Clock
         |    input a : UInt<1>
         |    wire HostClockSource : Clock
         |    inst ila_wrapper_inst of ila_wrapper
         |
         |    wire b : UInt<8>
         |    inst mod of Mod
         |    b <= UInt<8>("h0")
         |    ila_wrapper_inst.ila_b <= b
         |    ila_wrapper_inst.ila_a <= a
         |    ila_wrapper_inst.ila_mod_a <= mod.ila_a
         |    ila_wrapper_inst.clock <= HostClockSource
         |    HostClockSource <= clock
         |
         |  extmodule ila_wrapper :
         |    input clock : Clock
         |    input ila_b : UInt<8>
         |    input ila_a : UInt<1>
         |    input ila_mod_a : UInt<1>
         |    defname = ila_wrapper
         |  module Mod:
         |   output a : UInt<1>
         |   output ila_a : UInt<1>
         |   a <= UInt<1>("h0")
         |   ila_a <= a
         |""".stripMargin

    def fpgaDebugAnnos = Seq(
      FirrtlFpgaDebugAnnotation(ModuleTarget("Top", "Top").ref("a")),
      FirrtlFpgaDebugAnnotation(ModuleTarget("Top", "Top").ref("b")),
      FirrtlFpgaDebugAnnotation(ModuleTarget("Top", "Mod").ref("a")),
    )

    def ipgenAnno = GoldenGateOutputFileAnnotation(
      """|create_ip -name ila \
         |  -vendor xilinx.com \
         |  -library ip \
         |  -version  6.2 \
         |  -module_name ila_firesim
         |set_property -dict [list \
         |  CONFIG.C_PROBE0_WIDTH {8}  CONFIG.C_PROBE0_MU_CNT {2} \
         |  CONFIG.C_PROBE1_WIDTH {1}  CONFIG.C_PROBE1_MU_CNT {2} \
         |  CONFIG.C_PROBE2_WIDTH {1}  CONFIG.C_PROBE2_MU_CNT {2} \
         |  CONFIG.C_NUM_OF_PROBES {3} \
         |  CONFIG.C_DATA_DEPTH {1024} \
         |  CONFIG.C_TRIGOUT_EN {false} \
         |  CONFIG.C_EN_STRG_QUAL {1} \
         |  CONFIG.C_ADV_TRIGGER {true} \
         |  CONFIG.C_TRIGIN_EN {false} \
         |  CONFIG.ALL_PROBE_SAME_MU_CNT {2}] [get_ips ila_firesim]
         |""".stripMargin,
      ".ila_firesim.ipgen.tcl",
    )

    def bbAnno = BlackBoxInlineAnno(
      topMT.copy(module = "ila_wrapper").toNamed,
      AutoILATransform.ilaWrapperFilename(baseAnnos),
      """|// A wrapper module around the ILA IP instance. This serves two purposes:
         |// 1. It gives the probes reasonable names in the GUI
         |// 2. Verilog ifdefs the remove the ILA instantiation in metasimulation.
         |module ila_wrapper (
         |    input clock,
         |input [7:0] ila_b,
         |input [0:0] ila_a,
         |input [0:0] ila_mod_a
         |);
         |// Don't instantiate the ILA when running under metasimulation
         |`ifdef SYNTHESIS
         |  ila_firesim CL_FIRESIM_DEBUG_WIRING_TRANSFORM (
         |    .clk(clock),
         |    .probe0 (ila_b),
         |    .probe1 (ila_a),
         |    .probe2 (ila_mod_a)
         |  );
         |`endif
         |endmodule
         |""".stripMargin,
    )

    val referenceAnnos = Seq(bbAnno, ipgenAnno)
    it should "wire out the correct ILA when a variety of targets are labelled" in {
      executeWithAnnos(input, output, fpgaDebugAnnos ++ baseAnnos, referenceAnnos)
    }
  }
}
