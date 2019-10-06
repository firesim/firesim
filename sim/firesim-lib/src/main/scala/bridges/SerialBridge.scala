//See LICENSE for license details
package firesim.bridges

import midas.widgets._

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import freechips.rocketchip.config.Parameters

import testchipip.SerialIO

class SerialBridge extends BlackBox with Bridge[HostPortIO[SerialBridgeTargetIO], SerialBridgeModule] {
  val io = IO(new SerialBridgeTargetIO)
  val bridgeIO = HostPort(io)
  val constructorArg = None
  generateAnnotations()
}

object SerialBridge {
  def apply(port: SerialIO)(implicit p: Parameters): SerialBridge = {
    val ep = Module(new SerialBridge)
    ep.io.serial <> port
    ep
  }
}

class SerialBridgeTargetIO extends Bundle {
  val serial = Flipped(new SerialIO(testchipip.SerialAdapter.SERIAL_IF_WIDTH))
  val reset = Input(Bool())
}

class SerialBridgeModule(implicit p: Parameters) extends BridgeModule[HostPortIO[SerialBridgeTargetIO]]()(p) {
  val io = IO(new WidgetIO)
  val hPort = IO(HostPort(new SerialBridgeTargetIO))

  val serialBits = testchipip.SerialAdapter.SERIAL_IF_WIDTH
  val inBuf  = Module(new Queue(UInt(serialBits.W), 16))
  val outBuf = Module(new Queue(UInt(serialBits.W), 16))
  val tokensToEnqueue = RegInit(0.U(32.W))

  val target = hPort.hBits.serial
  val tFire = hPort.toHost.hValid && hPort.fromHost.hReady && tokensToEnqueue =/= 0.U
  val targetReset = tFire & hPort.hBits.reset
  inBuf.reset  := reset.toBool || targetReset
  outBuf.reset := reset.toBool || targetReset

  hPort.toHost.hReady := tFire
  hPort.fromHost.hValid := tFire

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
