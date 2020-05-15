// See LICENSE for license details.

package goldengate.tests

import midas.passes._

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.testutils._

class BridgeTopWiringSpec extends MiddleTransformSpec with FirrtlRunners  {

   def transform = new BridgeTopWiring("t_")

   "The signal x in module A" should s"should be wired to Top with the correct clocks" in {
      val input =
         """circuit Top :
           |  module Top :
           |    input clock1 : Clock
           |    input clock2 : Clock
           |    inst a1 of A
           |    a1.clock <= clock1
           |    inst a2 of A
           |    a2.clock <= clock2
           |  module A :
           |    input clock : Clock
           |    wire x : UInt<1>
           |    x <= UInt(1)
           """.stripMargin
      val aIT = ModuleTarget("Top", "A")
      val topMT = ModuleTarget("Top", "Top")
      val annos = Seq(BridgeTopWiringAnnotation(aIT.ref("x"), aIT.ref("clock")))
      val checkAnnos = Seq(
        BridgeTopWiringOutputAnnotation(aIT.ref("x"),
                                        aIT.addHierarchy("Top", "a1").ref("x"),
                                        topMT.ref("t_a1_x"),
                                        topMT.ref("clock1"),
                                        topMT.ref("t_clock1")),
        BridgeTopWiringOutputAnnotation(aIT.ref("x"),
                                        aIT.addHierarchy("Top", "a2").ref("x"),
                                        topMT.ref("t_a2_x"),
                                        topMT.ref("clock2"),
                                        topMT.ref("t_clock2")))
      val check =
         """circuit Top :
           |  module Top :
           |    input clock1: Clock
           |    input clock2: Clock
           |    output t_a1_x: UInt<1>
           |    output t_a2_x: UInt<1>
           |    output t_clock1: Clock
           |    output t_clock2: Clock
           |    inst a1 of A
           |    inst a2 of A
           |    a1.clock <= clock1
           |    a2.clock <= clock2
           |    t_a1_x <= a1.t_x
           |    t_a2_x <= a2.t_x
           |    t_clock1 <= clock1
           |    t_clock2 <= clock2
           |  module A :
           |    input clock : Clock
           |    output t_x : UInt<1>
           |    wire x : UInt<1>
           |    x <= UInt(1)
           |    t_x <= x
           """.stripMargin
      executeWithAnnos(input, check, annos, checkAnnos)
   }
}
