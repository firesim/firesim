/// See LICENSE for license details.

package midas.widgets

import chisel3._
import junctions._
import freechips.rocketchip.amba.axi4.AXI4Bundle
import org.chipsalliance.cde.config.Parameters
import midas.platform.xilinx.{XilinxAXI4Bundle}

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

  def apply(io: XilinxAXI4Bundle, name: String): Unit = {
    val cyclecount = RegInit(0.U(64.W))
    cyclecount := cyclecount + 1.U
    when (io.awready && io.awvalid) {
      printf(s"[${name},awfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x, user %x\n",
        cyclecount,
        io.awaddr,
        io.awlen.get,
        io.awsize.get,
        io.awburst.get,
        io.awlock.get,
        io.awcache.get,
        io.awprot,
        io.awqos.get,
        io.awid.get,
        0.U
        )
    }

    when (io.wready && io.wvalid) {
      printf(s"[${name},wfire,%x] data %x, last %x, strb %x\n",
        cyclecount,
        io.wdata,
        io.wlast.get,
        io.wstrb,
        )
    }

    when (io.bready && io.bvalid) {
      printf(s"[${name},bfire,%x] resp %x, id %x, user %x\n",
        cyclecount,
        io.bresp,
        io.bid.get,
        0.U
        )
    }

    when (io.arready && io.arvalid) {
      printf(s"[${name},arfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x, user %x\n",
        cyclecount,
        io.araddr,
        io.arlen.get,
        io.arsize.get,
        io.arburst.get,
        io.arlock.get,
        io.arcache.get,
        io.arprot,
        io.arqos.get,
        io.arid.get,
        0.U
        )
    }

    when (io.rready && io.rvalid) {
      printf(s"[${name},rfire,%x] resp %x, data %x, last %x, id %x, user %x\n",
        cyclecount,
        io.rresp,
        io.rdata,
        io.rlast.get,
        io.rid.get,
        0.U
        )
    }
  }

}
