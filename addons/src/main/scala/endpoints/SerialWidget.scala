package midas
package top
package endpoints

import core._
import widgets._

import chisel3._
import chisel3.util._
import config.Parameters

import junctions.SerialIO

class SimSerialIO extends Endpoint {
  def matchType(data: Data) = data match {
    case channel: SerialIO => channel.out.valid.dir == OUTPUT
    case _ => false
  }
  def widget(p: Parameters) = new SerialWidget()(p)
  override def widgetName = "SerialWidget"
}

class SerialWidgetIO(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val w = p(testchipip.SerialInterfaceWidth)
  val hPort = Flipped(HostPort(new SerialIO(w)))
}

class SerialWidget(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new SerialWidgetIO)

  val inBuf  = Module(new Queue(UInt(io.w.W), 8))
  val outBuf = Module(new Queue(UInt(io.w.W), 8))

  val target = io.hPort.hBits
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid
  val targetReset = tFire & io.tReset.bits
  inBuf.reset  := reset || targetReset
  outBuf.reset := reset || targetReset

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
  genROReg(inBuf.io.enq.ready, "in_ready")
  genROReg(outBuf.io.deq.bits, "out_bits")
  genROReg(outBuf.io.deq.valid, "out_valid")
  Pulsify(genWORegInit(outBuf.io.deq.ready, "out_ready", false.B), pulseLength = 1)

  genROReg(!tFire, "done")

  genCRFile()
}
