// See LICENSE for license details.

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
  // This endpoint will connect "SerialIO" in top-level IO
  // SerialIO is a channel for riscv-fesvr
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

  // Buffer for target input
  val inBuf  = Module(new Queue(UInt(io.w.W), 8))
  // Buffer for target output
  val outBuf = Module(new Queue(UInt(io.w.W), 8))

  val target = io.hPort.hBits
  // firing condition
  // 1. tokens from the target are presented (io.hPort.toHost.hValid)
  // 2. the target is ready to accept tokens (io.hPort.fromHost.hReady)
  // 3. target reset tokens are presented (io.tReset.valid)
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid
  val targetReset = tFire & io.tReset.bits
  // reset buffers with target reset
  inBuf.reset  := reset || targetReset
  outBuf.reset := reset || targetReset

  // tokens from the target are consumed with firing condition
  io.hPort.toHost.hReady := tFire
  // tokens toward the target are generated with firing condition
  io.hPort.fromHost.hValid := tFire
  // target reset tokens are consumed with firing condition
  io.tReset.ready := tFire

  // Connect serial input to target.in
  target.in.bits  := inBuf.io.deq.bits
  target.in.valid := inBuf.io.deq.valid
  // the data should be consumed with firing condition
  inBuf.io.deq.ready := target.in.ready && tFire

  // Connect target.out to serial output
  outBuf.io.enq.bits := target.out.bits
  // the data should be generated with firing condition
  outBuf.io.enq.valid := target.out.valid && tFire && !io.tReset.bits
  target.out.ready := outBuf.io.enq.ready

  // Generate memory mapped registers for buffers
  genWOReg(inBuf.io.enq.bits, "in_bits")
  Pulsify(genWORegInit(inBuf.io.enq.valid, "in_valid", false.B), pulseLength = 1)
  genROReg(inBuf.io.enq.ready, "in_ready")
  genROReg(outBuf.io.deq.bits, "out_bits")
  genROReg(outBuf.io.deq.valid, "out_valid")
  Pulsify(genWORegInit(outBuf.io.deq.ready, "out_ready", false.B), pulseLength = 1)

  // generate memory mapped registers for control signals
  // The endpoint is "done" when tokens from the target are not available any more
  genROReg(!tFire, "done")

  genCRFile()
}
