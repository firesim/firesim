// See LICENSE for license details.

package goldengate.tests.core

import chisel3._
import chisel3.util._
import chisel3.experimental.DataMirror

import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec

import midas.core.SimUtils

import firrtl.ir.{BundleType, Default, Field, IntWidth, NoInfo, Port, UIntType}
import firrtl.annotations.{ReferenceTarget, TargetToken}

class SimUtilsSpec extends AnyFlatSpec {
  val portInt    = new Port(
    NoInfo,
    "port_int",
    firrtl.ir.Input,
    BundleType(
      Seq(
        Field("bits", Default, UIntType(IntWidth(32)))
      )
    ),
  )
  val portIntRef = new ReferenceTarget("Test", "Test", Seq(), "port_int", Seq())

  val portBundle    = new Port(
    NoInfo,
    "port_bundle",
    firrtl.ir.Input,
    BundleType(
      Seq(
        Field(
          "bits",
          Default,
          BundleType(
            Seq(
              Field("a", Default, UIntType(IntWidth(32))),
              Field("b", Default, UIntType(IntWidth(42))),
              Field("c", Default, UIntType(IntWidth(64))),
            )
          ),
        )
      )
    ),
  )
  val portBundleRef =
    new ReferenceTarget("Test", "Test", Seq(), "port_bundle", Seq())

  val portMap = Map(
    portIntRef    -> portInt,
    portBundleRef -> portBundle,
  )

  def checkFields(ref: Data, src: Seq[(String, Data)]): Unit = {
    val refRecord = ref.asInstanceOf[Record]
    refRecord.elements should equal src
  }

  "SimUtils" should "decode primitive port" in {
    val data = SimUtils.buildChannelType(
      portMap,
      Seq(
        portIntRef.copy(component = Seq(TargetToken.Field("bits")))
      ),
    )
    require(data.typeEquivalent(UInt(32.W)))
  }

  "SimUtils" should "flatten bundle" in {
    val data = SimUtils.buildChannelType(
      portMap,
      Seq(
        portBundleRef.copy(component = Seq(TargetToken.Field("bits"), TargetToken.Field("a")))
      ),
    )
    require(data.typeEquivalent(UInt(32.W)))
  }

  "SimUtils" should "filter ports" in {
    val data = SimUtils.buildChannelType(
      portMap,
      Seq(
        portBundleRef.copy(component = Seq(TargetToken.Field("bits"), TargetToken.Field("a"))),
        portBundleRef.copy(component = Seq(TargetToken.Field("bits"), TargetToken.Field("b"))),
      ),
    )
    checkFields(
      data,
      Seq(
        "a" -> UInt(32.W),
        "b" -> UInt(42.W),
      ),
    )
  }

  "SimUtils" should "return whole bundle" in {
    val data = SimUtils.buildChannelType(
      portMap,
      Seq(
        portBundleRef.copy(component = Seq(TargetToken.Field("bits"), TargetToken.Field("a"))),
        portBundleRef.copy(component = Seq(TargetToken.Field("bits"), TargetToken.Field("b"))),
        portBundleRef.copy(component = Seq(TargetToken.Field("bits"), TargetToken.Field("c"))),
      ),
    )
    checkFields(
      data,
      Seq(
        "a" -> UInt(32.W),
        "b" -> UInt(42.W),
        "c" -> UInt(64.W),
      ),
    )
  }
}
