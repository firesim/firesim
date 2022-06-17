/// See LICENSE for license details.

package junctions

import chisel3._
import chisel3.util.experimental.InlineInstance

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters}

class AXI42NastiIdentityModule(params: AXI4BundleParameters)(implicit p: Parameters)
    extends RawModule with InlineInstance {
  val io = IO(new Bundle {
    val axi4 = Flipped(new AXI4Bundle(params))
    val nasti = new NastiIO()(p alterPartial { case NastiKey => NastiParameters(params) } )
  })
  AXI4NastiAssigner.toNasti(io.nasti, io.axi4)
}

class Nasti2AXI4IdentityModule(params: AXI4BundleParameters)(implicit p: Parameters)
    extends RawModule with InlineInstance {
  val io = IO(new Bundle {
    val axi4 = new AXI4Bundle(params)
    val nasti = Flipped(new NastiIO()(p alterPartial { case NastiKey => NastiParameters(params) } ))
  })
  AXI4NastiAssigner.toAXI4(io.axi4, io.nasti)
}

class Nasti2AXI4Monitor(params: AXI4BundleParameters)(implicit p: Parameters)
    extends RawModule with InlineInstance {
  val io = IO(new Bundle {
    val axi4 = Output(new AXI4Bundle(params))
    val nasti = Input(new NastiIO()(p alterPartial { case NastiKey => NastiParameters(params) } ))
  })
  import chisel3.ExplicitCompileOptions.NotStrict
  io.axi4 := io.nasti
}

/**
  * THe Nasti -> AXI4 implies here that all methods of this object accept
  * NastiIO as their primary argument. NB: the Nasti bundle may be mastered or be mastered
  * by the resulting AXI4.
  *
  */
object Nasti2AXI4 {
  // Coerces a nastiIO bundle to all source-flow for use in a monitor or printf
  def toMonitor(nastiIO: NastiIO)(implicit p: Parameters): AXI4Bundle = {
    val axi4Params =  AXI4BundleParameters(nastiIO.ar.bits.addr.getWidth,
                                           nastiIO.r.bits.data.getWidth,
                                           nastiIO.ar.bits.id.getWidth)
    val conv = Module(new Nasti2AXI4Monitor(axi4Params))
    conv.io.nasti := nastiIO
    conv.io.axi4
  }
}

/**
  * THe AXI4 -> Nasti implies here that all methods of this object accept
  * AXI4 as their primary argument.
  *
  */
object AXI42Nasti {
  // Returns an nasti bundle that drives the argument (a sink-flow AXI4 bundle).
  def fromSink(axi4Sink: AXI4Bundle)(implicit p: Parameters): NastiIO = {
    val conv = Module(new Nasti2AXI4IdentityModule(axi4Sink.params))
    axi4Sink <> conv.io.axi4
    conv.io.nasti
  }
}
