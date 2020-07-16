// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest._

class ClockSource(periodPS: BigInt, dutyCycle: Int = 50, initValue: Boolean = false) extends MultiIOModule {
  val clockOut = IO(Decoupled(new TimestampedToken(Bool())))
  val time = RegInit(0.U(clockOut.bits.timestampWidth.W))
  val highTime = (periodPS * dutyCycle) / 100
  val lowTime = periodPS - highTime
  val currentValue = RegInit(initValue.B)
  clockOut.valid := true.B
  clockOut.bits.time := time
  clockOut.bits.data := currentValue

  when(clockOut.ready) {
    time := Mux(currentValue, highTime.U, lowTime.U) + time
    currentValue := ~currentValue
  }
}

class ClockSourceReference(periodPS: BigInt, dutyCycle: Int = 50, initValue: Int = 0)
    extends BlackBox(Map("PERIOD_PS" -> periodPS, "DUTY_CYCLE" -> dutyCycle, "INIT_VALUE" -> initValue))
    with HasBlackBoxResource {
  addResource("/midas/widgets/ReferenceClockSource.sv")
  val io = IO(new Bundle { val clockOut = Output(Bool()) })
}

object ClockSource {
  def instantiateAgainstReference(periodPS: BigInt, dutyCycle: Int = 50, initValue: Boolean = false):
      (Bool, DecoupledIO[TimestampedToken[Bool]]) = {
    val reference = Module(new ClockSourceReference(periodPS, initValue = if (initValue) 1 else 0))
    val model = Module(new ClockSource(periodPS, initValue = initValue))
    (reference.io.clockOut, model.clockOut)
  }
}


class ClockSourceTest(periodPS: BigInt, initValue: Boolean, timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) with HasTimestampConstants{
  val (reference, model) = ClockSource.instantiateAgainstReference(periodPS, initValue = initValue)
  val targetTime = TimestampedTokenTraceEquivalence(reference, model)
  io.finished := targetTime > (timeout / 2).U
}

