package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.ParameterizedBundle
import midas.core.DMANastiKey

class F1ShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(MasterNastiKey) })))
  val dma    = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
  val slave  = new NastiIO()(p alterPartial ({ case NastiKey => p(SlaveNastiKey) }))
}

class F1Shim(simIo: midas.core.SimWrapperIO)
              (implicit p: Parameters) extends PlatformShim {
  val io = IO(new F1ShimIO)
  val top = Module(new midas.core.FPGATop(simIo))
  val headerConsts = List(
    "MMIO_WIDTH" -> p(MasterNastiKey).dataBits / 8,
    "MEM_WIDTH"  -> p(SlaveNastiKey).dataBits / 8
  ) ++ top.headerConsts

  val cyclecount = Reg(init = UInt(0, width=64.W))
  cyclecount := cyclecount + UInt(1)

  // print all transactions
  when (io.master.aw.fire()) {
    printf("[master,awfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, region %x, id %x, user %x\n",
      cyclecount,
      io.master.aw.bits.addr,
      io.master.aw.bits.len,
      io.master.aw.bits.size,
      io.master.aw.bits.burst,
      io.master.aw.bits.lock,
      io.master.aw.bits.cache,
      io.master.aw.bits.prot,
      io.master.aw.bits.qos,
      io.master.aw.bits.region,
      io.master.aw.bits.id,
      io.master.aw.bits.user
      )
  }

  when (io.master.w.fire()) {
    printf("[master,wfire,%x] data %x, last %x, id %x, strb %x, user %x\n",
      cyclecount,
      io.master.w.bits.data,
      io.master.w.bits.last,
      io.master.w.bits.id,
      io.master.w.bits.strb,
      io.master.w.bits.user
      )
  }

  when (io.master.b.fire()) {
    printf("[master,bfire,%x] resp %x, id %x, user %x\n",
      cyclecount,
      io.master.b.bits.resp,
      io.master.b.bits.id,
      io.master.b.bits.user
      )
  }

  when (io.master.ar.fire()) {
    printf("[master,arfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, region %x, id %x, user %x\n",
      cyclecount,
      io.master.ar.bits.addr,
      io.master.ar.bits.len,
      io.master.ar.bits.size,
      io.master.ar.bits.burst,
      io.master.ar.bits.lock,
      io.master.ar.bits.cache,
      io.master.ar.bits.prot,
      io.master.ar.bits.qos,
      io.master.ar.bits.region,
      io.master.ar.bits.id,
      io.master.ar.bits.user
      )
  }

  when (io.master.r.fire()) {
    printf("[master,rfire,%x] resp %x, data %x, last %x, id %x, user %x\n",
      cyclecount,
      io.master.r.bits.resp,
      io.master.r.bits.data,
      io.master.r.bits.last,
      io.master.r.bits.id,
      io.master.r.bits.user
      )
  }

  when (io.dma.aw.fire()) {
    printf("[dma,awfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, region %x, id %x, user %x\n",
      cyclecount,
      io.dma.aw.bits.addr,
      io.dma.aw.bits.len,
      io.dma.aw.bits.size,
      io.dma.aw.bits.burst,
      io.dma.aw.bits.lock,
      io.dma.aw.bits.cache,
      io.dma.aw.bits.prot,
      io.dma.aw.bits.qos,
      io.dma.aw.bits.region,
      io.dma.aw.bits.id,
      io.dma.aw.bits.user)
  }

  when (io.dma.w.fire()) {
    printf("[dma,wfire,%x] data %x, last %x, id %x, strb %x, user %x\n",
      cyclecount,
      io.dma.w.bits.data,
      io.dma.w.bits.last,
      io.dma.w.bits.id,
      io.dma.w.bits.strb,
      io.dma.w.bits.user)
  }

  when (io.dma.b.fire()) {
    printf("[dma,bfire,%x] resp %x, id %x, user %x\n",
      cyclecount,
      io.dma.b.bits.resp,
      io.dma.b.bits.id,
      io.dma.b.bits.user)
  }

  when (io.dma.ar.fire()) {
    printf("[dma,arfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, region %x, id %x, user %x\n",
      cyclecount,
      io.dma.ar.bits.addr,
      io.dma.ar.bits.len,
      io.dma.ar.bits.size,
      io.dma.ar.bits.burst,
      io.dma.ar.bits.lock,
      io.dma.ar.bits.cache,
      io.dma.ar.bits.prot,
      io.dma.ar.bits.qos,
      io.dma.ar.bits.region,
      io.dma.ar.bits.id,
      io.dma.ar.bits.user)
  }

  when (io.dma.r.fire()) {
    printf("[dma,rfire,%x] resp %x, data %x, last %x, id %x, user %x\n",
      cyclecount,
      io.dma.r.bits.resp,
      io.dma.r.bits.data,
      io.dma.r.bits.last,
      io.dma.r.bits.id,
      io.dma.r.bits.user)
  }

  when (io.slave.aw.fire()) {
    printf("[slave,awfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, region %x, id %x, user %x\n",
      cyclecount,

      io.slave.aw.bits.addr,
      io.slave.aw.bits.len,
      io.slave.aw.bits.size,
      io.slave.aw.bits.burst,
      io.slave.aw.bits.lock,
      io.slave.aw.bits.cache,
      io.slave.aw.bits.prot,
      io.slave.aw.bits.qos,
      io.slave.aw.bits.region,
      io.slave.aw.bits.id,
      io.slave.aw.bits.user
      )
  }

  when (io.slave.w.fire()) {
    printf("[slave,wfire,%x] data %x, last %x, id %x, strb %x, user %x\n",
      cyclecount,

      io.slave.w.bits.data,
      io.slave.w.bits.last,
      io.slave.w.bits.id,
      io.slave.w.bits.strb,
      io.slave.w.bits.user
      )
  }

  when (io.slave.b.fire()) {
    printf("[slave,bfire,%x] resp %x, id %x, user %x\n",
      cyclecount,

      io.slave.b.bits.resp,
      io.slave.b.bits.id,
      io.slave.b.bits.user
      )
  }

  when (io.slave.ar.fire()) {
    printf("[slave,arfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, region %x, id %x, user %x\n",
      cyclecount,

      io.slave.ar.bits.addr,
      io.slave.ar.bits.len,
      io.slave.ar.bits.size,
      io.slave.ar.bits.burst,
      io.slave.ar.bits.lock,
      io.slave.ar.bits.cache,
      io.slave.ar.bits.prot,
      io.slave.ar.bits.qos,
      io.slave.ar.bits.region,
      io.slave.ar.bits.id,
      io.slave.ar.bits.user
      )
  }

  when (io.slave.r.fire()) {
    printf("[slave,rfire,%x] resp %x, data %x, last %x, id %x, user %x\n",
      cyclecount,

      io.slave.r.bits.resp,
      io.slave.r.bits.data,
      io.slave.r.bits.last,
      io.slave.r.bits.id,
      io.slave.r.bits.user
      )
  }

  top.io.ctrl <> io.master
  top.io.dma  <> io.dma
  io.slave <> top.io.mem

  val (wCounterValue, wCounterWrap) = Counter(io.master.aw.fire(), 4097)
  top.io.ctrl.aw.bits.id := wCounterValue

  val (rCounterValue, rCounterWrap) = Counter(io.master.ar.fire(), 4097)
  top.io.ctrl.ar.bits.id := rCounterValue

}
