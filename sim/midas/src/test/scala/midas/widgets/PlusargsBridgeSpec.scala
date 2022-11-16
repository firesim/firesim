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

  class SimpleInstantiate extends Module {
    val out = IO(Output(UInt(5.W)))
    val cfg = PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("7"), width = 5)
    out := PlusargsBridge.drive(cfg)
  }

  class SimpleInstantiateApplyObject extends Module {
    val cfg = PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("7"), width = 6)
    val ret = PlusargsBridge.apply(cfg)
    assert(ret.io.out.getWidth == 6)
  }

  class SimpleInstantiateApplyParameters extends Module {
    val ret = PlusargsBridge.apply(name = "plusar_v=%d", default = BigInt("7"), docstring = "doc", width = 33)
    assert(ret.io.out.getWidth == 33)
  }

  def elaborateBlackBox(mod: => Module): Unit =
    ChiselStage.emitChirrtl(mod)

  "PlusargsBridge" should "normal instantiate" in {
    elaborateBlackBox(new SimpleInstantiate)
  }

  "PlusargsBridge" should "apply with object" in {
    elaborateBlackBox(new SimpleInstantiateApplyObject)
  }

  "PlusargsBridge" should "apply with parameters" in {
    elaborateBlackBox(new SimpleInstantiateApplyParameters)
  }

  "PlusargsBridge" should "reject default value too large" in {
    assertThrows[java.lang.IllegalArgumentException] {
      PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("4276993775"), width = 29)
    }
  }

  "PlusargsBridge" should "reject zero width" in {
    assertThrows[java.lang.IllegalArgumentException] {
      PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("0"), width = 0)
    }
  }

  "PlusargsBridge" should "type must match" in {
    assertThrows[java.lang.IllegalArgumentException] {
      PlusargsBridgeParams(name = "plusar_v=%f", default = BigInt("1"), width = 32)
    }
  }

  "PlusargsBridge" should "type must accept %d" in {
    PlusargsBridgeParams(name = "plusar_v=%d", default = BigInt("1"), width = 32)
  }
}
