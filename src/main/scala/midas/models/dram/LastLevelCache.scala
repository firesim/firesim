package midas
package models

// NOTE: This LLC model is *very* crude model of a cache that simple forwards
// misses onto the DRAM model, while short-circuiting hits.
import junctions._

import midas.core._
import midas.widgets._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.{ParameterizedBundle, MaskGen}

import chisel3._
import chisel3.util._

import scala.math.min
import Console.{UNDERLINED, RESET}

import java.io.{File, FileWriter}

// State to track reads to DRAM, ~loosely an MSHR
class MSHR(llcKey: LLCKey)(implicit p: Parameters) extends NastiBundle()(p) {
  val set_addr = UInt(llcKey.sets.maxBits.W)
  val xaction = new TransactionMetaData
  val wb_in_flight = Bool()
  val acq_in_flight = Bool()

  def valid(): Bool = wb_in_flight || acq_in_flight
  def setCollision(set_addr: UInt): Bool = (set_addr === this.set_addr) && valid()

  override def cloneType = new MSHR(llcKey)(p).asInstanceOf[this.type]
}

object MSHR {
  def apply(llcKey: LLCKey)(implicit p: Parameters): MSHR = {
    val w = Wire(new MSHR(llcKey))
    w.wb_in_flight := false.B
    w.acq_in_flight := false.B
    w.xaction := DontCare
    w.set_addr := DontCare
    w
  }
  def apply(
      llcKey: LLCKey,
      xaction: TransactionMetaData,
      set_addr: UInt,
      do_acq: Bool,
      do_wb: Bool = false.B)(implicit p: Parameters): MSHR = {
    val w = Wire(new MSHR(llcKey))
    w.set_addr := set_addr
    w.wb_in_flight := do_wb
    w.acq_in_flight := do_acq
    w.xaction := xaction
    w
  }
}

class BlockMetadata(tagBits: Int) extends Bundle {
  val tag = UInt(tagBits.W)
  val valid = Bool()
  val dirty = Bool()
  override def cloneType = new BlockMetadata(tagBits).asInstanceOf[this.type]
}

class LLCProgrammableSettings(llcKey: LLCKey) extends Bundle
    with HasProgrammableRegisters with HasConsoleUtils {
  val wayBits    = Input(UInt(log2Ceil(llcKey.ways.maxBits).W))
  val setBits    = Input(UInt(log2Ceil(llcKey.sets.maxBits).W))
  val blockBits  = Input(UInt(log2Ceil(llcKey.blockBytes.maxBits).W))

  val registers = Seq(
    wayBits   -> RuntimeSetting(2, "Log2(ways per set)"),
    setBits   -> RuntimeSetting(4, "Log2(sets per bank"),
    blockBits -> RuntimeSetting(6, "Log2(cache-block bytes")
  )

  def maskTag(addr: UInt): UInt = (addr >> (blockBits + setBits))
  def maskSet(addr: UInt): UInt = ((addr >> blockBits) & ((1.U << setBits) - 1.U))(llcKey.sets.maxBits-1, 0)
  def regenPhysicalAddress(bank_addr: UInt, set_addr: UInt, tag_addr: UInt): UInt =
    (bank_addr << (blockBits)) |
    (set_addr << (blockBits)) |
    (tag_addr << (blockBits + setBits))

  def setLLCSettings(bytesPerBlock: Option[Int] = None): Unit = {
    Console.println(s"\n${UNDERLINED}Last-Level Cache Settings${RESET}")

    regMap(blockBits).set(log2Ceil(requestInput("Block size in bytes", 128,
                                                min = Some(llcKey.blockBytes.min),
                                                max = Some(llcKey.sets.max))))
    regMap(wayBits).set(log2Ceil(requestInput("Number of sets in LLC", 4096,
                                               min = Some(llcKey.sets.min),
                                               max = Some(llcKey.sets.max))))
    regMap(setBits).set(log2Ceil(requestInput("Set associativity", 8,
                                              min = Some(llcKey.ways.min),
                                              max = Some(llcKey.ways.max))))
  }

}

case class WRange(min: Int, max: Int) {
  def minBits: Int = log2Ceil(min)
  def maxBits: Int = log2Ceil(max)
}

case class LLCKey(
    ways: WRange       = WRange(1, 8),
    sets: WRange       = WRange(32, 4096),
    blockBytes: WRange = WRange(8, 128),
    banks: WRange      = WRange(1, 1),
    mshrs: WRange      = WRange(1, 8)// TODO: check against AXI ID width
  ) {

  def maxTagBits(addrWidth: Int): Int =
    addrWidth - blockBytes.minBits - banks.minBits - sets.minBits
}

class LLCModelIO(key: LLCKey)(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val req = Flipped(new NastiReqChannels)
  val wResp = Decoupled(new WriteResponseMetaData) // to backend
  val rResp = Decoupled(new ReadResponseMetaData)
  val memReq = new NastiReqChannels                  // to backing DRAM model
  val memRResp = Flipped(Decoupled(new ReadResponseMetaData)) // from backing DRAM model
  val memWResp = Flipped(Decoupled(new WriteResponseMetaData))

  // LLC runtime configuration
  val settings = new LLCProgrammableSettings(key)
}

class LLCModel(cfg: BaseConfig)(implicit p: Parameters) extends NastiModule()(p) {
  val llcKey = cfg.params.llcKey.get
  val io = IO(new LLCModelIO(llcKey))

  val maxTagBits = llcKey.maxTagBits(nastiXAddrBits)

  val active_ways = MaskGen(((1 << llcKey.ways.max) - 1).U, 1.U << io.settings.wayBits, 1 << llcKey.ways.max)
  val way_addr_mask =  MaskGen((llcKey.ways.max - 1).U, io.settings.wayBits, llcKey.ways.max)

  // Behold: this is why it sucks to be a hardware model
  val md_array = SyncReadMem(llcKey.sets.max, Vec(llcKey.ways.max, new BlockMetadata(maxTagBits)))
  val d_array_busy = Module(new DownCounter(8))
  d_array_busy.io.set.valid := false.B
  d_array_busy.io.set.bits := DontCare
  d_array_busy.io.decr := false.B

  val mshrs =  RegInit(Vec.fill(llcKey.mshrs.max)(MSHR(llcKey)))
  val mshr_available = mshrs.exists({ m: MSHR => !m.valid() })
  val mshr_next_idx = mshrs.indexWhere({ m: MSHR => !m.valid() })

  val s2_ar_mem = Module(new Queue(new NastiReadAddressChannel, 2))
  val s2_aw_mem = Module(new Queue(new NastiWriteAddressChannel, 2))

  val reads = Queue(io.req.ar)
  val read_set = io.settings.maskSet(reads.bits.addr)
  val read_set_collision = mshrs.exists({ m: MSHR => m.setCollision(read_set) })
  val can_deq_read = reads.valid && !read_set_collision && mshr_available && s2_ar_mem.io.enq.ready &&
    io.rResp.ready

  val writes = Queue(io.req.aw)
  val write_set = io.settings.maskSet(writes.bits.addr)
  val write_set_collision = mshrs.exists({ m: MSHR => m.setCollision(write_set) })
  val can_deq_write = writes.valid && !write_set_collision && mshr_available && s2_aw_mem.io.enq.ready &&
    io.wResp.ready

  val llc_idle :: llc_r_mdaccess :: llc_r_wb :: llc_r_daccess :: llc_w_mdaccess :: llc_w_wb :: llc_w_daccess :: llc_refill :: Nil = Enum(8)

  val state = RegInit(llc_idle)
  val refill_start = WireInit(false.B)
  val read_start   = WireInit(false.B)
  val write_start  = WireInit(false.B)

  val set_addr = Mux(can_deq_write, write_set, read_set)
  val tag_addr = io.settings.maskTag(Mux(can_deq_write, writes.bits.addr, reads.bits.addr))

  // S1 = Tag matches, replacement candidate selection, and replacement policy update
  val s1_tag_addr = RegNext(tag_addr)
  val s1_set_addr = RegNext(set_addr)
  val s1_valid = state === llc_r_mdaccess || state === llc_w_mdaccess
  val s1_metadata = {
    import Chisel._
    md_array.read(set_addr, read_start || write_start)
  }

  def isHit(m: BlockMetadata): Bool = m.valid && (m.tag === s1_tag_addr)
  val hit_way_OH  = Vec(s1_metadata.map(isHit)).asUInt
  val hit_valid   = hit_way_OH.orR

  def isEmptyWay(m: BlockMetadata): Bool = !m.valid
  val empty_valid  = s1_metadata.exists(isEmptyWay _)
  val empty_way_OH = PriorityEncoderOH(Vec(s1_metadata map isEmptyWay).asUInt)
  val fill_empty_way = !hit_valid && empty_valid

  val lsfr = LFSR16(true.B)
  val evict_way_OH = UIntToOH(lsfr(log2Ceil(llcKey.ways.max) - 1, 0) & way_addr_mask)
  val evict_way_is_dirty = (Vec(s1_metadata.map(_.dirty)).asUInt & evict_way_OH).orR
  val evict_way_tag = Mux1H(evict_way_OH, s1_metadata.map(_.tag))

  val do_evict         = !hit_valid && !empty_valid
  val evict_dirty_way  = do_evict && evict_way_is_dirty
  val dirty_line_addr = io.settings.regenPhysicalAddress(0.U, s1_set_addr, evict_way_tag)

  val selected_way_OH = Mux(hit_valid, hit_way_OH, Mux(empty_valid, empty_way_OH, evict_way_OH)).toBools

  val md_update = s1_metadata.zip(selected_way_OH) map { case (md, sel) =>
    val next = Wire(init = md)
    when (sel) {
      when (fill_empty_way) {
        next.valid := true.B
      }
      when(state === llc_w_mdaccess) {
        next.dirty := true.B
      }.elsewhen(state === llc_r_mdaccess && do_evict) {
        next.dirty := false.B
      }
      when(do_evict || fill_empty_way) {
        next.tag := tag_addr
      }
    }
    next
  }
  md_array.write(s1_valid, Vec(md_update))

  val allocate_mshr = (state === llc_w_mdaccess && evict_dirty_way) ||
                      (state === llc_r_mdaccess && !hit_valid)

  when(allocate_mshr) {
    mshrs(mshr_next_idx) := MSHR(
      llcKey,
      xaction  = Mux(state === llc_r_mdaccess,
                    TransactionMetaData(reads.bits),
                    TransactionMetaData(writes.bits)),
      set_addr = s1_set_addr,
      do_acq   = (state === llc_r_mdaccess),
      do_wb    = evict_dirty_way)
  }

  // AXI4 length; subtract 1
  val outer_block_beats = (1.U << (io.settings.blockBits - log2Ceil(nastiXDataBits/8).U)) - 1.U
  // refills
  s2_ar_mem.io.enq.bits     := reads.bits
  s2_ar_mem.io.enq.bits.id  := mshr_next_idx
  s2_ar_mem.io.enq.bits.len := outer_block_beats
  s2_ar_mem.io.enq.valid    := (state === llc_r_mdaccess || state === llc_w_mdaccess) && allocate_mshr

  reads.ready := (state === llc_r_mdaccess)

  // writebacks
  s2_aw_mem.io.enq.bits := NastiWriteAddressChannel(
                            id   = mshr_next_idx,
                            addr = dirty_line_addr,
                            size = log2Ceil(nastiXDataBits/8).U,
                            len  = outer_block_beats)
  s2_aw_mem.io.enq.valid := s1_valid && evict_dirty_way

  writes.ready := io.req.w.bits.last && io.req.w.fire

  io.memReq.ar <> s2_ar_mem.io.deq
  io.memReq.aw <> s2_aw_mem.io.deq
  io.memReq.w.valid := (state === llc_r_wb || state === llc_w_wb)
  io.memReq.w.bits.last := d_array_busy.io.idle

  // Handle responses from DRAM
  when (io.memWResp.valid) {
    mshrs(io.memWResp.bits.id).wb_in_flight := false.B
  }
  io.memWResp.ready := true.B

  when (refill_start) {
    mshrs(io.memRResp.bits.id).acq_in_flight := false.B
  }
  val can_refill = io.memRResp.valid && io.rResp.ready
  io.memRResp.ready := refill_start

  // Data-array hazard tracking
  when (((state === llc_w_mdaccess || state === llc_r_mdaccess) && evict_dirty_way) ||
          refill_start) {
    d_array_busy.io.set.valid := true.B
    d_array_busy.io.set.bits  := outer_block_beats
  }.elsewhen (state === llc_r_mdaccess && hit_valid) {
    d_array_busy.io.set.valid := true.B
    d_array_busy.io.set.bits  := reads.bits.len
  }.elsewhen (state === llc_w_mdaccess && (hit_valid || empty_valid) ||
              state === llc_w_wb       && (io.memReq.w.fire && io.memReq.w.bits.last)) {
    d_array_busy.io.set.valid := true.B
    d_array_busy.io.set.bits  := writes.bits.len
  }

  d_array_busy.io.decr := Mux(state === llc_w_wb || state === llc_r_wb,
                              io.memReq.w.fire,
                              Mux(state === llc_w_daccess, io.req.w.valid, true.B))

  io.req.w.ready := (state === llc_w_daccess) || (state === llc_w_mdaccess && !evict_dirty_way)

  io.rResp.valid := refill_start || (state === llc_r_mdaccess && hit_valid)
  io.rResp.bits := Mux(refill_start,
                       ReadResponseMetaData(mshrs(io.memRResp.bits.id).xaction),
                       ReadResponseMetaData(reads.bits))

  io.wResp.valid := (state === llc_w_mdaccess || state === llc_w_daccess) &&
    io.req.w.valid && io.req.w.bits.last
  io.wResp.bits := WriteResponseMetaData(writes.bits)

  switch (state) {
    is (llc_idle) {
      when (can_refill) {
        state := llc_refill
        refill_start := true.B
      }.elsewhen(can_deq_write) {
        state := llc_w_mdaccess
        write_start := true.B
      }.elsewhen(can_deq_read) {
        state := llc_r_mdaccess
        read_start := true.B
      }
    }
    is (llc_r_mdaccess) {
      when (hit_valid) {
        state := llc_r_daccess
      }.elsewhen (evict_dirty_way) {
        state := llc_r_wb
      }.otherwise {
        state := llc_idle
      }
    }
    is (llc_w_mdaccess) {
      when (!evict_dirty_way) {
        when (io.req.w.valid && io.req.w.bits.last) {
          state := llc_idle
        }.otherwise {
          state := llc_w_daccess
        }
      }.otherwise {
        state := llc_w_wb
      }
    }
    is (llc_r_wb) {
      when(io.memReq.w.fire && io.memReq.w.bits.last) {
        state := llc_idle
      }
    }
    is (llc_w_wb) {
      when(io.memReq.w.fire && io.memReq.w.bits.last) {
        state := llc_w_daccess
      }
    }
    is (llc_w_daccess) {
      when (io.req.w.valid && io.req.w.bits.last) {
        state := llc_idle
      }
    }
    is (llc_r_daccess) {
      when (d_array_busy.io.current === 1.U) {
        state := llc_idle
      }
    }
    is (llc_refill) {
      when (d_array_busy.io.current === 1.U) {
        state := llc_idle
      }
    }
  }
}
