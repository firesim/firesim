package firesim
package endpoints

import midas.core.{HostPort}
import midas.widgets._

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import freechips.rocketchip.config.Parameters

import testchipip.SerialIO

class SimSerialIO extends Endpoint {
  def matchType(data: Data) = data match {
    case channel: SerialIO =>
      DataMirror.directionOf(channel.out.valid) == Direction.Output
    case _ => false
  }
  def widget(p: Parameters) = new SerialWidget()(p)
  override def widgetName = "SerialWidget"
}

class SerialWidgetIO(implicit val p: Parameters) extends EndpointWidgetIO()(p) {
  val w = testchipip.SerialAdapter.SERIAL_IF_WIDTH
  val hPort = Flipped(HostPort(new SerialIO(w)))
}

class SerialWidget(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new SerialWidgetIO)

  val inBuf  = Module(new Queue(UInt(io.w.W), 16))
  val outBuf = Module(new Queue(UInt(io.w.W), 16))
  val tokensToEnqueue = RegInit(0.U(32.W))

  val target = io.hPort.hBits
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid && tokensToEnqueue =/= 0.U
  val targetReset = tFire & io.tReset.bits
  inBuf.reset  := reset.toBool || targetReset
  outBuf.reset := reset.toBool || targetReset

  io.hPort.toHost.hReady := tFire
  io.hPort.fromHost.hValid := tFire
  io.tReset.ready := tFire

  target.in <> inBuf.io.deq
  inBuf.io.deq.ready := target.in.ready && tFire

  outBuf.io.enq <> target.out
  outBuf.io.enq.valid := target.out.valid && tFire

  genWOReg(inBuf.io.enq.bits, "in_bits")
  Pulsify(genWORegInit(inBuf.io.enq.valid, "in_valid", false.B), pulseLength = 1)
  genROReg(inBuf.io.enq.ready, "in_ready")
  genROReg(outBuf.io.deq.bits, "out_bits")
  genROReg(outBuf.io.deq.valid, "out_valid")
  Pulsify(genWORegInit(outBuf.io.deq.ready, "out_ready", false.B), pulseLength = 1)

  val stepSize = Wire(UInt(32.W))
  val start = Wire(Bool())
  when (start) {
    tokensToEnqueue := stepSize
  }.elsewhen (tFire) {
    tokensToEnqueue := tokensToEnqueue - 1.U
  }

  genWOReg(stepSize, "step_size")
  genROReg(tokensToEnqueue === 0.U, "done")
  Pulsify(genWORegInit(start, "start", false.B), pulseLength = 1)

  genCRFile()
}
