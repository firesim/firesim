//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util._
import chisel3.util.Enum
import freechips.rocketchip.config.{Config, Field, Parameters}

import midas.widgets._

import midas.widgets.ResetPulseBridge

/** Defines a test group with Token Hashers enabled. This piggy-backs on the PlusArgsTest, so PlusArgsTestNumberKey is
  * also included
  */
class EnableTokenHashersDefault
    extends Config((site, here, up) => {
      case InsertTokenHashersKey  => true
      case TokenHashersUseCounter => false
      case PlusArgsTestNumberKey  => 1
    })

/** Defines a test group with Token Hashers enabled, but in counter mode.
  */
class EnableTokenHashersCounter
    extends Config((site, here, up) => {
      case InsertTokenHashersKey  => true
      case TokenHashersUseCounter => true
      case PlusArgsTestNumberKey  => 1
    })

class TokenHashersModuleIO(val params: PlusArgsBridgeParams) extends Bundle {
  // Output value that PlusArgs bridge gives us
  val gotPlusArgValue = Output(UInt((params.width).W))
  val writeValue = Input(UInt((32).W))
  val readValue = Output(UInt((32).W))
  val readValueFlipped = Output(UInt((32).W))
}

/** A DUT to demonstrate usage of a PlusArgsBridge. Two test groups exist, which are referred to from
  * [[TutorialSuite.scala]]
  */
class TokenHashersDUT(implicit val p: Parameters) extends Module {

  def testGroup0(): PlusArgsBridgeParams = {
    PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("276783146634859761135"), width = 68)
  }

  def testGroup1(): PlusArgsBridgeParams = {
    PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("4"), width = 29)
  }

  val params = p(PlusArgsTestNumberKey) match {
    case 0 => testGroup0()
    case 1 => testGroup1()
    case _ => throw new RuntimeException(s"Test Group #{p(PlusArgsTestNumberKey)} does not exist")
  }

  val io = IO(new TokenHashersModuleIO(params))

  io.gotPlusArgValue := PlusArgsBridge.drive(params)

  io.readValue := io.writeValue
  io.readValueFlipped := ~io.writeValue
}

class TokenHashersModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new TokenHashersDUT)

// Just copy this
// class TokenHashersModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new PlusArgsDUT)
