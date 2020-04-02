package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.{LazyModule}
import freechips.rocketchip.util.HeterogeneousBag

import midas.core.{DMANastiKey, HostMemNumChannels}

case object AXIDebugPrint extends Field[Boolean]

class F1ShimIO(implicit val p: Parameters) extends Bundle {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(MasterNastiKey) })))
  val dma    = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
}

class F1Shim(implicit p: Parameters) extends PlatformShim {
  val io = IO(new F1ShimIO)
  val top = Module(LazyModule(new midas.core.FPGATop).module)
  val headerConsts = top.headerConsts

  val cyclecount = RegInit(0.U(64.W))
  cyclecount := cyclecount + 1.U

  if (p(AXIDebugPrint)) {
    // print all transactions
    when (io.master.aw.fire()) {
      printf("[master,awfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x, user %x\n",
        cyclecount,
        io.master.aw.bits.addr,
        io.master.aw.bits.len,
        io.master.aw.bits.size,
        io.master.aw.bits.burst,
        io.master.aw.bits.lock,
        io.master.aw.bits.cache,
        io.master.aw.bits.prot,
        io.master.aw.bits.qos,
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
      printf("[master,arfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x, user %x\n",
        cyclecount,
        io.master.ar.bits.addr,
        io.master.ar.bits.len,
        io.master.ar.bits.size,
        io.master.ar.bits.burst,
        io.master.ar.bits.lock,
        io.master.ar.bits.cache,
        io.master.ar.bits.prot,
        io.master.ar.bits.qos,
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
      printf("[dma,awfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x, user %x\n",
        cyclecount,
        io.dma.aw.bits.addr,
        io.dma.aw.bits.len,
        io.dma.aw.bits.size,
        io.dma.aw.bits.burst,
        io.dma.aw.bits.lock,
        io.dma.aw.bits.cache,
        io.dma.aw.bits.prot,
        io.dma.aw.bits.qos,
        io.dma.aw.bits.id,
        io.dma.aw.bits.user)
    }

    when (io.dma.w.fire()) {
      printf("[dma,wfire,%x] data %x, last %x, id %x, strb %x, user %x\n",
        cyclecount,
        io.dma.w.bits.data,
        io.dma.w.bits.last,
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
      printf("[dma,arfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x, user %x\n",
        cyclecount,
        io.dma.ar.bits.addr,
        io.dma.ar.bits.len,
        io.dma.ar.bits.size,
        io.dma.ar.bits.burst,
        io.dma.ar.bits.lock,
        io.dma.ar.bits.cache,
        io.dma.ar.bits.prot,
        io.dma.ar.bits.qos,
        io.dma.ar.bits.id)
    }

    when (io.dma.r.fire()) {
      printf("[dma,rfire,%x] resp %x, data %x, last %x, id %x\n",
        cyclecount,
        io.dma.r.bits.resp,
        io.dma.r.bits.data,
        io.dma.r.bits.last,
        io.dma.r.bits.id)
    }

  when (io_slave(0).aw.fire()) {
    printf("[slave,awfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x\n",
      cyclecount,

      io_slave(0).aw.bits.addr,
      io_slave(0).aw.bits.len,
      io_slave(0).aw.bits.size,
      io_slave(0).aw.bits.burst,
      io_slave(0).aw.bits.lock,
      io_slave(0).aw.bits.cache,
      io_slave(0).aw.bits.prot,
      io_slave(0).aw.bits.qos,
      io_slave(0).aw.bits.id
      )
  }

  when (io_slave(0).w.fire()) {
    printf("[slave(0),wfire,%x] data %x, last %x, id %x, strb %x\n",
      cyclecount,

      io_slave(0).w.bits.data,
      io_slave(0).w.bits.last,
      io_slave(0).w.bits.strb)
  }

  when (io_slave(0).b.fire()) {
    printf("[slave(0),bfire,%x] resp %x, id %x\n",
      cyclecount,

      io_slave(0).b.bits.resp,
      io_slave(0).b.bits.id
      )
  }

  when (io_slave(0).ar.fire()) {
    printf("[slave(0),arfire,%x] addr %x, len %x, size %x, burst %x, lock %x, cache %x, prot %x, qos %x, id %x\n",
      cyclecount,

      io_slave(0).ar.bits.addr,
      io_slave(0).ar.bits.len,
      io_slave(0).ar.bits.size,
      io_slave(0).ar.bits.burst,
      io_slave(0).ar.bits.lock,
      io_slave(0).ar.bits.cache,
      io_slave(0).ar.bits.prot,
      io_slave(0).ar.bits.qos,
      io_slave(0).ar.bits.id
      )
  }

  when (io_slave(0).r.fire()) {
    printf("[slave(0),rfire,%x] resp %x, data %x, last %x, id %x\n",
      cyclecount,

      io_slave(0).r.bits.resp,
      io_slave(0).r.bits.data,
      io_slave(0).r.bits.last,
      io_slave(0).r.bits.id
      )
  }
 }


  top.io.ctrl <> io.master
  top.io.dma  <> io.dma
  val io_slave = IO(HeterogeneousBag(top.mem.map(x => x.cloneType)))
  io_slave.zip(top.mem).foreach({ case (io, bundle) => io <> bundle })

  val (wCounterValue, wCounterWrap) = Counter(io.master.aw.fire(), 4097)
  top.io.ctrl.aw.bits.id := wCounterValue

  val (rCounterValue, rCounterWrap) = Counter(io.master.ar.fire(), 4097)
  top.io.ctrl.ar.bits.id := rCounterValue

}
