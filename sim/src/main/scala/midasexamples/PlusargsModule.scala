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
class PlusArgsModuleTestConfigGroup68Bit
    extends Config((site, here, up) => { case PlusArgsTestNumber =>
      0
    })

/** Defines a test group with the id of 1
  */
class PlusArgsModuleTestConfigGroup29Bit
    extends Config((site, here, up) => { case PlusArgsTestNumber =>
      1
    })

case object PlusArgsTestNumber extends Field[Int]

class PlusArgsModuleIO(val params: PlusArgsBridgeParams) extends Bundle {
  // Output value that PlusArgs bridge gives us
  val gotPlusArgValue = Output(UInt((params.width).W))
}

/** A DUT to demonstrate usage of a PlusArgsBridge. Two test groups exist, which are referred to from
  * [[TutorialSuite.scala]]
  */
class PlusArgsDUT(implicit val p: Parameters) extends Module {

  def testGroup0(): PlusArgsBridgeParams = {
    PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("276783146634859761135"), width = 68)
  }

  def testGroup1(): PlusArgsBridgeParams = {
    PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("4"), width = 29)
  }

  val params = p(PlusArgsTestNumber) match {
    case 0 => testGroup0()
    case 1 => testGroup1()
    case _ => throw new RuntimeException(s"Test Group #{p(PlusArgsTestNumber)} does not exist")
  }

  val io = IO(new PlusArgsModuleIO(params))

  io.gotPlusArgValue := PlusArgsBridge.drive(params)
}

class PlusArgsModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new PlusArgsDUT)
