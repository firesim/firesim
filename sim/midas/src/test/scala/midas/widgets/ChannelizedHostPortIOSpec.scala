// See LICENSE for license details.

package goldengate.tests.widgets

import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.experimental.{BaseModule, annotate, ChiselAnnotation}

import freechips.rocketchip.config.Parameters

import midas.widgets._

// Can't elaborate a top-level that is a blackbox, so wrap it
class BlackBoxWrapper(mod: => BaseModule) extends Module {
  val testMod = Module(mod)
}

class ChannelizedHostPortIOSpec extends AnyFlatSpec {
  // 
  class BridgeTargetIO extends Bundle {
    val out = Output(Bool())
    val in =  Input(Bool())
    val bidir = new Bundle {
      val in = Input(Bool())
      val out = Output(Bool())
    }
    val clock = Input(Clock())
  }

  // With the targetIO above, create a few simple misconfigurations by using
  // only a subset of the IO
  class IncorrectInputChannel(proto: BridgeTargetIO) extends Bundle with ChannelizedHostPortIO {
    def targetClockRef = proto.clock
    val inCh = InputChannel(proto.out)
  }

  class IncorrectOutputChannel(proto: BridgeTargetIO) extends Bundle with ChannelizedHostPortIO {
    def targetClockRef = proto.clock
    val outCh = OutputChannel(proto.in)
  }

  class DisallowedBidirChannel(proto: BridgeTargetIO) extends Bundle with ChannelizedHostPortIO {
    def targetClockRef = proto.clock
    val bidirCh = OutputChannel(proto.bidir)
  }

  class CorrectHostPort(proto: BridgeTargetIO) extends Bundle with ChannelizedHostPortIO {
    def targetClockRef = proto.clock
    val outCh = OutputChannel(proto.out)
    val inCh = InputChannel(proto.in)
    val bidirInCh = InputChannel(proto.bidir.in)
    val bidirOutCh = OutputChannel(proto.bidir.out)
  }

  class BridgeMock[T <: Bundle with ChannelizedHostPortIO](gen: (BridgeTargetIO) => T) extends BlackBox {
    outer =>
    val io = IO(new BridgeTargetIO)
    val bridgeIO = gen(io)
    // Spoof annotation generation
    annotate(new ChiselAnnotation { def toFirrtl = {
        BridgeAnnotation(
          outer.toTarget,
          bridgeIO.bridgeChannels,
          widgetClass = "NotARealBridge",
          widgetConstructorKey = None)
      }
    })
  }

  def elaborateBlackBox(mod: =>BaseModule): Unit = ChiselStage.emitChirrtl(new BlackBoxWrapper(mod))

  def checkElaborationRequirement(mod: =>BaseModule): Unit = {
    assertThrows[java.lang.IllegalArgumentException] {
      try {
        elaborateBlackBox(mod)
      } catch {
        // Strip away the outer ChiselException and rethrow to get a more precise cause
        case ce: ChiselException => println(ce.getCause); throw ce.getCause
      }
    }
  }

  "ChannelizedHostPortIO" should "reject outputs incorrectly labelled as input channels" in {
    checkElaborationRequirement(new BridgeMock( { new IncorrectInputChannel(_) } ))
  }
  it should "reject inputs incorreclty labelled as output channels" in {
    checkElaborationRequirement(new BridgeMock( { new IncorrectOutputChannel(_) } ))
  }
  it should "reject channels with bidirectional payloads"  in {
    checkElaborationRequirement(new BridgeMock( { new DisallowedBidirChannel(_) } ))
  }
  it should "elaborate correctly otherwise"  in {
    elaborateBlackBox(new BridgeMock( { new CorrectHostPort(_) }))
  }
}
