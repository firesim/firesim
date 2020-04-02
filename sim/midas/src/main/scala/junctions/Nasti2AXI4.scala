/// See LICENSE for license details.

package junctions

import chisel3._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters}

class AXI42NastiIdentityModule(params: AXI4BundleParameters)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val axi4 = Flipped(new AXI4Bundle(params))
    val nasti = new NastiIO()(p alterPartial { case NastiKey => NastiParameters(params) } )
  })

  import chisel3.ExplicitCompileOptions.NotStrict
  io.nasti <> io.axi4
  io.nasti.ar.bits.user := io.axi4.ar.bits.user.getOrElse(DontCare)
  io.nasti.aw.bits.user := io.axi4.aw.bits.user.getOrElse(DontCare)
}

class Nasti2AXI4IdentityModule(params: AXI4BundleParameters)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val axi4 = new AXI4Bundle(params)
    val nasti = Flipped(new NastiIO()(p alterPartial { case NastiKey => NastiParameters(params) } ))
  })
  import chisel3.ExplicitCompileOptions.NotStrict
  io.axi4 <> io.nasti
  io.nasti.r.bits.user := io.axi4.r.bits.user.getOrElse(DontCare)
  io.nasti.b.bits.user := io.axi4.b.bits.user.getOrElse(DontCare)
}

object Nasti2AXI4 {
  def convertFromAXI4Sink(axi4Sink: AXI4Bundle)(implicit p: Parameters): NastiIO = {
    val conv = Module(new Nasti2AXI4IdentityModule(axi4Sink.params))
    axi4Sink <> conv.io.axi4
    conv.io.nasti
  }
}
