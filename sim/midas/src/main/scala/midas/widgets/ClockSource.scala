// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.BaseModule

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest._


case class ClockSourceParams(periodPS: BigInt, dutyCycle: Int = 50, initValue: Boolean = true)

class ClockSource(params: ClockSourceParams) extends MultiIOModule {
  val ClockSourceParams(periodPS, dutyCycle, initValue) = params
  require(periodPS >= 2)
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

class ClockSourceReference(params: ClockSourceParams) extends BlackBox(Map(
    "PERIOD_PS" -> params.periodPS,
    "DUTY_CYCLE" -> params.dutyCycle,
    "INIT_VALUE" -> { if (params.initValue) 1 else 0 })) with HasBlackBoxResource {
  addResource("/midas/widgets/ReferenceClockSource.sv")
  val io = IO(new Bundle { val clockOut = Output(Bool()) })
}

object ClockSource {
  def instantiateAgainstReference(params: ClockSourceParams): (Bool, TimestampedTuple[Bool]) = {
    val reference = Module(new ClockSourceReference(params))
    val model = Module(new ClockSource(params))
    (reference.io.clockOut, TimestampedSource(DecoupledDelayer(model.clockOut, 0.5)))
  }

  def instantiateAgainstReference(periodPS: BigInt, dutyCycle: Int = 50, initValue: Boolean = false): (Bool, TimestampedTuple[Bool]) =
    instantiateAgainstReference(ClockSourceParams(periodPS, dutyCycle, initValue))

  def apply(periodPS: BigInt, dutyCycle: Int = 50, initValue: Boolean): ClockSource = 
    Module(new ClockSource(ClockSourceParams(periodPS, dutyCycle, initValue)))
}


class ClockSourceTest(params: ClockSourceParams, timeout: Int = 100000)(implicit p: Parameters) 
    extends UnitTest(timeout) with HasTimestampConstants{
  val (reference, model) = ClockSource.instantiateAgainstReference(params)
  io.finished := TimestampedTokenTraceEquivalence(reference, model, timeout)
}

class ClockSourceHostIO(private val srcClock: Clock = Clock()) extends Bundle with TimestampedHostPortIO {
  val clockOut = OutputClockChannel(srcClock)
}

private[midas] class BridgeableClockSource(mod: BaseModule, clockOut: Clock, params: ClockSourceParams)
    extends Bridgeable[ClockSourceHostIO, ClockSourceBridgeModule] {
  def target = mod.toNamed.toTarget
  def bridgeIO = new ClockSourceHostIO(clockOut)
  def constructorArg = Some(params)
}

object BridgeableClockSource {
  def apply(mod: BaseModule, clockOut: Clock, params: ClockSourceParams): Unit = {
    val annotator = new BridgeableClockSource(mod, clockOut, params)
    annotator.generateAnnotations()
  }
  def apply(mod: BaseModule, clockOut: Clock, periodPS: BigInt): Unit =
    apply(mod, clockOut, ClockSourceParams(periodPS))
}

/**
  * A dummy target-side module
  */
class BlackBoxClockSourceBridge(params: ClockSourceParams) extends BlackBox {
  val io = IO(new Bundle {
    val clockOut = Output(Clock())
  })
  BridgeableClockSource(this, io.clockOut, params)
}

class ClockSourceBridgeModule(params: ClockSourceParams)(implicit p: Parameters) extends BridgeModule[ClockSourceHostIO] {
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(new ClockSourceHostIO)
    hPort.clockOut <> Module(new ClockSource(params)).clockOut
  }
}


