//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util._
import chisel3.util.Enum
import freechips.rocketchip.config.{Config, Field, Parameters}

import midas.widgets._

import midas.widgets.ResetPulseBridge

/** Defines a test group with the id of 0
  */
class PlusargsModuleTestConfigGroup0
    extends Config((site, here, up) => { case PlusargsTestNumber =>
      0
    })

/** Defines a test group with the id of 1
  */
class PlusargsModuleTestConfigGroup1
    extends Config((site, here, up) => { case PlusargsTestNumber =>
      1
    })

case object PlusargsTestNumber extends Field[Int]

class PlusargsModuleIO(val params: PlusargsBridgeParams) extends Bundle {
  // Output value that plusargs bridge gives us
  val gotPlusargValue = Output(UInt((params.width).W))
}

/** A DUT to demonstrate usage of a PlusargsBridge. Two test groups exist, which are referred to from
  * [[TutorialSuite.scala]]
  */
class PlusargsDUT(implicit val p: Parameters) extends Module {

  def testGroup0(): PlusargsBridgeParams = {
    PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("276783146634859761135"), width = 68)
  }

  def testGroup1(): PlusargsBridgeParams = {
    PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("4"), width = 29)
  }

  val params = p(PlusargsTestNumber) match {
    case 0 => testGroup0()
    case 1 => testGroup1()
    case _ => throw new RuntimeException(s"Test Group #{p(PlusargsTestNumber)} does not exist")
  }

  val io = IO(new PlusargsModuleIO(params))

  io.gotPlusargValue := PlusargsBridge.drive(params)
}

class PlusargsModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new PlusargsDUT)
