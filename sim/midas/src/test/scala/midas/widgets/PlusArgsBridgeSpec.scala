// See LICENSE for license details.

package goldengate.tests.widgets

import chisel3._
import midas.widgets._

import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.experimental.BaseModule

/** Unit tests for [[PlusArgsBridge]]
  */
class PlusArgsBridgeSpec extends AnyFlatSpec {

  class SimpleInstantiate extends Module {
    val out = IO(Output(UInt(5.W)))
    val cfg = PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("7"), width = 5)
    out := PlusArgsBridge.drive(cfg)
  }

  class SimpleInstantiateApplyObject extends Module {
    val cfg = PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("7"), width = 6)
    val ret = PlusArgsBridge.apply(cfg)
    assert(ret.io.out.getWidth == 6)
  }

  class SimpleInstantiateApplyParameters extends Module {
    val ret = PlusArgsBridge.apply(name = "plusar_v=%d", default = BigInt("7"), docstring = "doc", width = 33)
    assert(ret.io.out.getWidth == 33)
  }

  def elaborateModule(mod: => Module): Unit =
    ChiselStage.emitChirrtl(mod)

  "PlusArgsBridge" should "normal instantiate" in {
    elaborateModule(new SimpleInstantiate)
  }

  "PlusArgsBridge" should "apply with object" in {
    elaborateModule(new SimpleInstantiateApplyObject)
  }

  "PlusArgsBridge" should "apply with parameters" in {
    elaborateModule(new SimpleInstantiateApplyParameters)
  }

  "PlusArgsBridge" should "reject default value too large" in {
    assertThrows[java.lang.IllegalArgumentException] {
      PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("4276993775"), width = 29)
    }
  }

  "PlusArgsBridge" should "reject zero width" in {
    assertThrows[java.lang.IllegalArgumentException] {
      PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("0"), width = 0)
    }
  }

  "PlusArgsBridge" should "type must match" in {
    assertThrows[java.lang.IllegalArgumentException] {
      PlusArgsBridgeParams(name = "plusar_v=%f", default = BigInt("1"), width = 32)
    }
  }

  "PlusArgsBridge" should "type must accept %d" in {
    PlusArgsBridgeParams(name = "plusar_v=%d", default = BigInt("1"), width = 32)
  }
}
