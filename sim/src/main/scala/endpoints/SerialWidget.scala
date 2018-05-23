package firesim
package endpoints

import midas.core._
import midas.widgets._

import chisel3.core._
import chisel3.util._
import DataMirror.directionOf
import freechips.rocketchip.config.Parameters

import testchipip.SerialIO

class SimSerialIO extends Endpoint {
  def matchType(data: Data) = data match {
    case channel: SerialIO =>
      directionOf(channel.out.valid) == ActualDirection.Output
    case _ => false
  }
  def widget(p: Parameters) = new SerialWidget()(p)
  override def widgetName = "SerialWidget"
}

class SerialWidgetIO(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val w = testchipip.SerialAdapter.SERIAL_IF_WIDTH
  val hPort = Flipped(HostPort(new SerialIO(w)))
  val dma = None
}

class SerialWidget(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new SerialWidgetIO)

  val inBuf  = Module(new Queue(UInt(io.w.W), 8))
  val outBuf = Module(new Queue(UInt(io.w.W), 8))

  val target = io.hPort.hBits
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid
  val targetReset = tFire & io.tReset.bits
  inBuf.reset  := reset.toBool || targetReset
  outBuf.reset := reset.toBool || targetReset

  io.hPort.toHost.hReady := tFire
  io.hPort.fromHost.hValid := tFire
  io.tReset.ready := tFire

  target.in.bits  := inBuf.io.deq.bits
  target.in.valid := inBuf.io.deq.valid
  inBuf.io.deq.ready := target.in.ready && tFire

  outBuf.io.enq.bits := target.out.bits
  outBuf.io.enq.valid := target.out.valid && tFire && !io.tReset.bits
  target.out.ready := outBuf.io.enq.ready

  genWOReg(inBuf.io.enq.bits, "in_bits")
  Pulsify(genWORegInit(inBuf.io.enq.valid, "in_valid", false.B), pulseLength = 1)

  /* concat low-width values to reduce # of PCIe reads */
  val catMMIOread = Cat(inBuf.io.enq.ready, outBuf.io.deq.valid)
  genROReg(catMMIOread, "in_ready_out_valid")
  genROReg(outBuf.io.deq.bits, "out_bits")
  Pulsify(genWORegInit(outBuf.io.deq.ready, "out_ready", false.B), pulseLength = 1)

  genROReg(!tFire, "done")

  genCRFile()
}
