// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}

import scala.collection.immutable.ListMap

class AsyncResetSourceTargetIO extends Bundle {
  val reset = Output(AsyncReset())
}

/**  Ideally this would be a Bundle, but the targetPortProto needs to be private
  *  so as to not be included in the bundles fields.
  */
class AsyncResetSourceHostIO(protected val targetPortProto: AsyncResetSourceTargetIO) extends Record with TimestampedHostPortIO {
  val reset = OutputAsyncResetChannel(targetPortProto.reset)
  val elements = ListMap("reset" -> reset)
  def cloneType() = new AsyncResetSourceHostIO(targetPortProto).asInstanceOf[this.type]
}

object AsyncResetPulseSource {
  def apply(pulseLengthPS: Int): AsyncReset = Module(new AsyncResetPulseSource(pulseLengthPS)).io.reset
}

// Pulse start is non-zero until we hash out time-zero behavior
case class AsyncResetPulseSourceCtorArgument(
    pulseLengthPS: Int,
    pulseStartPS: BigInt = 1,
    activeHigh: Boolean = true) {
  require(pulseStartPS > 0)
  require(pulseLengthPS > 0)
}

class AsyncResetPulseSource(pulseLengthPS: Int) extends BlackBox with Bridge[AsyncResetSourceHostIO, AsyncResetPulseBridgeModule] {
  val io = IO(new AsyncResetSourceTargetIO)
  val bridgeIO = new AsyncResetSourceHostIO(io)
  val constructorArg = Some(AsyncResetPulseSourceCtorArgument(pulseLengthPS))
  generateAnnotations()
}


class AsyncResetPulseBridgeModule(arg: AsyncResetPulseSourceCtorArgument)(implicit p: Parameters)
    extends BridgeModule[AsyncResetSourceHostIO] with HasTimestampConstants {
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(new AsyncResetSourceHostIO(new AsyncResetSourceTargetIO()))
    val initsimulationTime = RegInit(0.U(timestampWidth.W))
    val AsyncResetPulseSourceCtorArgument(pulseLengthPS, pulseStartPS, activeHigh) = arg

    val s_init :: s_assert :: s_deassert :: s_freerun :: s_done :: Nil = Enum(5)
    val state = RegInit(s_init)
    val nextState = WireDefault(state)

    hPort.reset.valid := state =/= s_done
    hPort.reset.bits.data := (!activeHigh).B
    when (state === s_init) {
      hPort.reset.bits.time := 0.U
      nextState := s_assert
    }.elsewhen (state === s_assert) {
      hPort.reset.bits.data := activeHigh.B
      hPort.reset.bits.time := pulseStartPS.U
      nextState := s_deassert
    }.elsewhen (state === s_deassert) {
      hPort.reset.bits.time := (pulseStartPS + pulseLengthPS).U
      nextState := s_freerun
    }.elsewhen (state === s_freerun) {
      hPort.reset.bits.time := maxTime.U
      nextState := s_done
    }
    when(hPort.reset.ready) { state := nextState }
  }
}
