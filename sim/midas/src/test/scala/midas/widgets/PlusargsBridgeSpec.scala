// See LICENSE for license details.

package goldengate.tests.widgets

import chisel3._
import midas.widgets._

import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.experimental.BaseModule

/** Unit tests for [[PlusargsBridge]]
  */
class PlusargsBridgeSpec extends AnyFlatSpec {

  class PlusargsModuleIO(val params: PlusargsBridgeParams) extends Bundle {
    val gotPlusargValue = Output(UInt((params.width).W))
  }

  // With the targetIO above, create a few simple misconfigurations by using
  // only a subset of the IO
  class CorrectInput(proto: PlusargsModuleIO) extends Bundle {}

  class BridgeMock[T <: Bundle](gen: (PlusargsModuleIO) => T, params: PlusargsBridgeParams) extends BlackBox {
    PlusargsBridge(params)
    val io       = IO(new PlusargsModuleIO(params))
    val bridgeIO = gen(io)
  }

  def elaborateBlackBox(mod: => BaseModule): Unit =
    ChiselStage.emitChirrtl(new BlackBoxWrapper(withClockAndReset(Input(Clock()), Input(Bool())) { mod }))

  def checkElaborationRequirement(mod: => BaseModule): Unit = {
    assertThrows[java.lang.IllegalArgumentException] {
      try {
        elaborateBlackBox(mod)
      } catch {
        // Strip away the outer ChiselException and rethrow to get a more precise cause
        case ce: ChiselException => println(ce.getCause); throw ce.getCause
      }
    }
  }

  "PlusargsBridge" should "reject default value too large" in {
    val cfg = PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("4276993775"), width = 29)
    checkElaborationRequirement(new BridgeMock({ new CorrectInput(_) }, cfg))
  }

  "PlusargsBridge" should "reject zero width" in {
    val cfg = PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("0"), width = 0)
    checkElaborationRequirement(new BridgeMock({ new CorrectInput(_) }, cfg))
  }

  "PlusargsBridge" should "type must match" in {
    val cfg = PlusargsBridgeParams(name = "plusar_v=%f", default = BigInt("1"), width = 32)
    checkElaborationRequirement(new BridgeMock({ new CorrectInput(_) }, cfg))
  }
}
