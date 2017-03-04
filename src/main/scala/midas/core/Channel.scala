package midas
package core

import config.Parameters

import chisel3._
import chisel3.util._

class ChannelIO(w: Int)(implicit p: Parameters) extends _root_.util.ParameterizedBundle()(p) {
  val in    = Flipped(Decoupled(UInt(width=w)))
  val out   = Decoupled(UInt(width=w))
  val trace = Decoupled(UInt(width=w))
  val traceLen = UInt(INPUT, log2Up(p(TraceMaxLen)+1))
}

class Channel(val w: Int)(implicit p: Parameters) extends Module {
  val io = IO(new ChannelIO(w))
  val tokens = Module(new Queue(UInt(width=w), p(ChannelLen)))
  tokens.io.enq <> io.in
  io.out <> tokens.io.deq
  if (p(EnableSnapshot)) {
    io.trace <> TraceQueue(tokens.io.deq, io.traceLen)
  } else {
    io.trace.valid := Bool(false)
  }
}
