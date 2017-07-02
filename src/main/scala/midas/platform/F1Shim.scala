package midas
package platform

import util.ParameterizedBundle // from rocketchip

import chisel3._
import chisel3.util._
import junctions._
import config.{Parameters, Field}

class F1ShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(MasterNastiKey) })))
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

  // report b resp to master and to our tracker
  val b_resps_queue = Module(new Queue(Bool(), 10))
  top.io.ctrl.aw <> Queue(io.master.aw, 10)
  top.io.ctrl.w <> Queue(io.master.w, 10)
  val write_b_frommidas = Queue(top.io.ctrl.b, 10)
  io.master.b.bits := write_b_frommidas.bits
  b_resps_queue.io.enq.bits := Bool(true)
  io.master.b.valid := write_b_frommidas.valid && b_resps_queue.io.enq.ready
  b_resps_queue.io.enq.valid := write_b_frommidas.valid && io.master.b.ready
  write_b_frommidas.ready := io.master.b.ready && b_resps_queue.io.enq.ready

  // reads appear to be serialized, so a lot of this is probably unnecessary
  val from_cpu_arq = Queue(io.master.ar, 10)
  val write_status_request_queue = Module(new Queue(Bool(), 4))
  val is_write_req = from_cpu_arq.bits.addr(2)
  write_status_request_queue.io.enq.bits := is_write_req
  from_cpu_arq.ready := (top.io.ctrl.ar.ready && !is_write_req) || (write_status_request_queue.io.enq.ready && is_write_req)
  top.io.ctrl.ar.bits := from_cpu_arq.bits
  top.io.ctrl.ar.bits.addr := (from_cpu_arq.bits.addr >> UInt(3)) << UInt(2)
  top.io.ctrl.ar.valid := from_cpu_arq.valid && !is_write_req
  write_status_request_queue.io.enq.valid := from_cpu_arq.valid && is_write_req

  val from_midas_rq = Queue(top.io.ctrl.r, 10)
  io.master.r.valid := from_midas_rq.valid || write_status_request_queue.io.deq.valid
  write_status_request_queue.io.deq.ready := io.master.r.ready // write status request always gets priority
  b_resps_queue.io.deq.ready := io.master.r.ready && write_status_request_queue.io.deq.valid
  from_midas_rq.ready := io.master.r.ready && !write_status_request_queue.io.deq.valid
  io.master.r.bits.resp := Mux(write_status_request_queue.io.deq.valid, UInt(0), from_midas_rq.bits.resp)
  io.master.r.bits.data := Mux(write_status_request_queue.io.deq.valid, b_resps_queue.io.deq.valid && b_resps_queue.io.deq.bits, from_midas_rq.bits.data)
  io.master.r.bits.last := Mux(write_status_request_queue.io.deq.valid, UInt(1), from_midas_rq.bits.last)
  io.master.r.bits.id := Mux(write_status_request_queue.io.deq.valid, UInt(0), from_midas_rq.bits.id)
  io.master.r.bits.user := Mux(write_status_request_queue.io.deq.valid, UInt(0), from_midas_rq.bits.user)

  io.slave.aw <> Queue(top.io.mem.aw, 10)
  io.slave.w <> Queue(top.io.mem.w, 10)
  top.io.mem.b <> Queue(io.slave.b, 10)

  io.slave.ar <> Queue(top.io.mem.ar, 10)
  top.io.mem.r <> Queue(io.slave.r, 10)

}
