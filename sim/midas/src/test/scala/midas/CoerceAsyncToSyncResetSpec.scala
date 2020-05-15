// See LICENSE for license details.

package goldengate.tests

import midas.passes._

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.testutils._
import logger._

class CoerceAsyncToSyncResetSpec extends LowTransformSpec with FirrtlRunners  {

   def transform = CoerceAsyncToSyncReset
   "Async reset ports" should s"be swapped with Bools" in {
      val input =
         """circuit Top :
           |  module Top :
           |    input reset : AsyncReset
           |    output o_reset : AsyncReset
           |    o_reset <= reset
           """.stripMargin
      val check =
         """circuit Top :
           |  module Top :
           |    input reset : UInt<1>
           |    output o_reset : UInt<1>
           |    o_reset <= reset
           """.stripMargin
      execute(input, check, Nil)
   }

   "Abstract reset ports" should s"be swapped with Bools, should they exist" in {
      val input =
         """circuit Top :
           |  module Top :
           |    input reset : AsyncReset
           |    output o_reset : Reset
           |    o_reset <= reset
           """.stripMargin
      val check =
         """circuit Top :
           |  module Top :
           |    input reset : UInt<1>
           |    output o_reset : UInt<1>
           |    o_reset <= reset
           """.stripMargin
      execute(input, check, Nil)
   }
   "asAsyncReset PrimOps" should "be replaced with asUInt" in {
      val input =
         """circuit Top :
           |  module Top :
           |    input reset : UInt<1>
           |    output o_reset : AsyncReset
           |    o_reset <= asAsyncReset(reset)
           """.stripMargin
      val check =
         """circuit Top :
           |  module Top :
           |    input reset : UInt<1>
           |    output o_reset : UInt<1>
           |    o_reset <= asUInt(reset)
           """.stripMargin
      execute(input, check, Nil)
   }

 "asAsyncReset PrimOps on constants" should "be replaced with asUInt" in {
    val input =
       """circuit Top :
         |  module Top :
         |    output o_reset : AsyncReset
         |    o_reset <= asAsyncReset(UInt<1>(1))
         """.stripMargin
    val check =
       """circuit Top :
         |  module Top :
         |    output o_reset : UInt<1>
         |    o_reset <= asUInt(UInt<1>(1))
         """.stripMargin
    execute(input, check, Nil)
  }
}
