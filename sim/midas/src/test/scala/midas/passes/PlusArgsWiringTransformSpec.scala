// See LICENSE for license details.

package midas.tests

import firrtl.annotations._
import firrtl.testutils._

import midas.passes.PlusArgsWiringTransform
import midas.targetutils.PlusArgFirrtlAnnotation

class PlusArgsWiringTransformSpec extends LowTransformSpec with FirrtlRunners {

  def transform = PlusArgsWiringTransform

  def midMT   = ModuleTarget("Top", "Mid")
  def inAnno  = PlusArgFirrtlAnnotation(midMT.instOf("plusarg_reader_i", "plusarg_reader"))
  val inAnnos = Seq(inAnno)

  behavior.of("PlusArgsWiringTransform")

  {
    val input =
      """|circuit Top :
         |  module Top :
         |    input clock : Clock
         |    output a : UInt<32>
         |    inst mid_i of Mid
         |    mid_i.clock <= clock
         |    a <= mid_i.a
         |  module Mid :
         |    input clock : Clock
         |    output a : UInt<32>
         |    inst plusarg_reader_i of plusarg_reader
         |    a <= plusarg_reader_i.out
         |  extmodule plusarg_reader :
         |    output out : UInt<32>
         |    defname = plusarg_reader
         |    parameter DEFAULT = 5
         |    parameter FORMAT = "plusarg_flag=%d"
         |    parameter WIDTH = 32
         |""".stripMargin

    val output =
      """|circuit Top :
         |  module Top :
         |    input clock : Clock
         |    output a : UInt<32>
         |
         |    inst mid_i of Mid
         |    a <= mid_i.a
         |    mid_i.clock <= clock
         |
         |  module Mid :
         |    input clock : Clock
         |    output a : UInt<32>
         |
         |    inst PlusArgBridge_plusarg_flag of PlusArgBridge_plusarg_flag
         |    node plusarg_reader_i_plusarg_wire = PlusArgBridge_plusarg_flag.io_out
         |    a <= plusarg_reader_i_plusarg_wire
         |    PlusArgBridge_plusarg_flag.clock <= clock
         |
         |  extmodule PlusArgBridge_plusarg_flag :
         |    input clock : Clock
         |    output io_out : UInt<32>
         |    defname = PlusArgBridge_plusarg_flag
         |""".stripMargin

    // TODO: should properly check the output annotation
    it should "do stuff" in {
      executeWithAnnos(input, output, inAnnos, Seq.empty)
    }
  }
}
