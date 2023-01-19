/// See LICENSE for license details.

package midas.widgets

import chisel3._
import junctions._
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.config.Parameters

object AXI4Printf {
  def apply(io: AXI4Bundle, name: String): Unit = {
    val cyclecount = RegInit(0.U(64.W))
    cyclecount := cyclecount + 1.U
    when (io.aw.fire) {
      printf(s"[${name},awfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x, user %x\n",
        cyclecount,
        io.aw.bits.addr,
        io.aw.bits.len,
        io.aw.bits.size,
        io.aw.bits.burst,
        io.aw.bits.lock,
        io.aw.bits.cache,
        io.aw.bits.prot,
        io.aw.bits.qos,
        io.aw.bits.id,
        io.aw.bits.user.asUInt
        )
    }

    when (io.w.fire) {
      printf(s"[${name},wfire,%x] data %x, last %x, strb %x\n",
        cyclecount,
        io.w.bits.data,
        io.w.bits.last,
        io.w.bits.strb,
        )
    }

    when (io.b.fire) {
      printf(s"[${name},bfire,%x] resp %x, id %x, user %x\n",
        cyclecount,
        io.b.bits.resp,
        io.b.bits.id,
        io.b.bits.user.asUInt
        )
    }

    when (io.ar.fire) {
      printf(s"[${name},arfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x, user %x\n",
        cyclecount,
        io.ar.bits.addr,
        io.ar.bits.len,
        io.ar.bits.size,
        io.ar.bits.burst,
        io.ar.bits.lock,
        io.ar.bits.cache,
        io.ar.bits.prot,
        io.ar.bits.qos,
        io.ar.bits.id,
        io.ar.bits.user.asUInt
        )
    }

    when (io.r.fire) {
      printf(s"[${name},rfire,%x] resp %x, data %x, last %x, id %x, user %x\n",
        cyclecount,
        io.r.bits.resp,
        io.r.bits.data,
        io.r.bits.last,
        io.r.bits.id,
        io.r.bits.user.asUInt
        )
    }
  }

  def apply(io: NastiIO, name: String)(implicit p: Parameters): Unit = apply(Nasti2AXI4.toMonitor(io), name)
}
