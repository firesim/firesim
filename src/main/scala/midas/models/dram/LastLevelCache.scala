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
class MSHR(llcKey: LLCParams)(implicit p: Parameters) extends NastiBundle()(p) {
  val set_addr = UInt(llcKey.sets.maxBits.W)
  val xaction = new TransactionMetaData
  val wb_in_flight = Bool()
  val acq_in_flight = Bool()

  def valid(): Bool = wb_in_flight || acq_in_flight
  def setCollision(set_addr: UInt): Bool = (set_addr === this.set_addr) && valid()

  override def cloneType = new MSHR(llcKey)(p).asInstanceOf[this.type]
}

object MSHR {
  def apply(llcKey: LLCParams)(implicit p: Parameters): MSHR = {
    val w = Wire(new MSHR(llcKey))
    w.wb_in_flight := false.B
    w.acq_in_flight := false.B
    w.xaction := DontCare
    w.set_addr := DontCare
    w
  }
  def apply(
      llcKey: LLCParams,
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

class LLCProgrammableSettings(llcKey: LLCParams) extends Bundle
    with HasProgrammableRegisters with HasConsoleUtils {
  val wayBits    = Input(UInt(log2Ceil(llcKey.ways.maxBits).W))
  val setBits    = Input(UInt(log2Ceil(llcKey.sets.maxBits).W))
  val blockBits  = Input(UInt(log2Ceil(llcKey.blockBytes.maxBits).W))

  // Instrumentation
  val misses     = Output(UInt(32.W)) // Total accesses is provided by (totalReads + totalWrites)
  val writebacks = Output(UInt(32.W)) // Number of dirty lines returned to DRAM
  val refills    = Output(UInt(32.W)) // Number of clean lines requested from DRAM
  // Note short-burst writes will produce a refill, whereas releases from caches will not

  val registers = Seq(
    wayBits   -> RuntimeSetting(3, "Log2(ways per set)"),
    setBits   -> RuntimeSetting(9, "Log2(sets per bank"),
    blockBits -> RuntimeSetting(6, "Log2(cache-block bytes")
  )

  def maskTag(addr: UInt): UInt = (addr >> (blockBits + setBits))
  def maskSet(addr: UInt): UInt = ((addr >> blockBits) & ((1.U << setBits) - 1.U))(llcKey.sets.maxBits-1, 0)
  def regenPhysicalAddress(set_addr: UInt, tag_addr: UInt): UInt =
    (set_addr << (blockBits)) |
    (tag_addr << (blockBits + setBits))

  def setLLCSettings(bytesPerBlock: Option[Int] = None): Unit = {
    Console.println(s"\n${UNDERLINED}Last-Level Cache Settings${RESET}")

    regMap(blockBits).set(log2Ceil(requestInput("Block size in bytes", 128,
                                                min = Some(llcKey.blockBytes.min),
                                                max = Some(llcKey.sets.max))))
    regMap(setBits).set(log2Ceil(requestInput("Number of sets in LLC", 4096,
                                               min = Some(llcKey.sets.min),
                                               max = Some(llcKey.sets.max))))
    regMap(wayBits).set(log2Ceil(requestInput("Set associativity", 8,
                                              min = Some(llcKey.ways.min),
                                              max = Some(llcKey.ways.max))))
  }

}

case class WRange(min: Int, max: Int) {
  def minBits: Int = log2Ceil(min)
  def maxBits: Int = log2Ceil(max)
  override def toString(): String = s"[${min},${max}]"
}

case class LLCParams(
    ways: WRange       = WRange(1, 8),
    sets: WRange       = WRange(32, 4096),
    blockBytes: WRange = WRange(8, 128),
    mshrs: WRange      = WRange(1, 8)// TODO: check against AXI ID width
  ) {

  def maxTagBits(addrWidth: Int): Int =
    addrWidth - blockBytes.minBits - sets.minBits

  def print(): Unit = {
    println("  LLC Parameters:")
    println("    Sets:              " + sets)
    println("    Associativity:     " + ways)
    println("    Block Size (B):    " + blockBytes)
    println("    MSHRs:             " + mshrs)
    println("    Replacement Policy: Random\n")
  }
}

class LLCModelIO(val key: LLCParams)(implicit val p: Parameters) extends Bundle {
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
  val way_addr_mask = Reverse(MaskGen((llcKey.ways.max - 1).U, io.settings.wayBits, llcKey.ways.max))

  // Behold: this is why it sucks to be a hardware model
  val md_array = SyncReadMem(llcKey.sets.max, Vec(llcKey.ways.max, new BlockMetadata(maxTagBits)))
  val d_array_busy = Module(new DownCounter(8))
  d_array_busy.io.set.valid := false.B
  d_array_busy.io.set.bits := DontCare
  d_array_busy.io.decr := false.B

  val mshrs =  RegInit(VecInit(Seq.fill(llcKey.mshrs.max)(MSHR(llcKey))))
  val mshr_available = mshrs.exists({ m: MSHR => !m.valid() })
  val mshr_next_idx = mshrs.indexWhere({ m: MSHR => !m.valid() })

  val s2_ar_mem = Module(new Queue(new NastiReadAddressChannel, 2))
  val s2_aw_mem = Module(new Queue(new NastiWriteAddressChannel, 2))
  val miss_resource_hazard = !mshr_available || !s2_aw_mem.io.enq.ready || !s2_ar_mem.io.enq.ready

  val reads = Queue(io.req.ar)
  val read_set = io.settings.maskSet(reads.bits.addr)
  val read_set_collision = mshrs.exists({ m: MSHR => m.setCollision(read_set) })
  val can_deq_read = reads.valid && !read_set_collision && !miss_resource_hazard && io.rResp.ready

  val writes = Queue(io.req.aw)
  val write_set = io.settings.maskSet(writes.bits.addr)
  val write_set_collision = mshrs.exists({ m: MSHR => m.setCollision(write_set) })
  val can_deq_write = writes.valid && !write_set_collision && !miss_resource_hazard && mshr_available && io.wResp.ready

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
  val hit_ways  = VecInit(s1_metadata.map(isHit)).asUInt & way_addr_mask
  val hit_way_sel = PriorityEncoderOH(hit_ways)
  val hit_valid   = hit_ways.orR

  def isEmptyWay(m: BlockMetadata): Bool = !m.valid
  val empty_ways    = VecInit(s1_metadata.map(isEmptyWay)).asUInt & way_addr_mask
  val empty_way_sel = PriorityEncoderOH(empty_ways)
  val empty_valid   = empty_ways.orR

  val fill_empty_way = !hit_valid && empty_valid

  val lsfr = LFSR16(true.B)
  val evict_way_sel = UIntToOH(lsfr(llcKey.ways.maxBits - 1, 0) & ((1.U << io.settings.wayBits) - 1.U))
  val evict_way_is_dirty = (VecInit(s1_metadata.map(_.dirty)).asUInt & evict_way_sel).orR
  val evict_way_tag = Mux1H(evict_way_sel, s1_metadata.map(_.tag))

  val do_evict         = !hit_valid && !empty_valid
  val evict_dirty_way  = do_evict && evict_way_is_dirty
  val dirty_line_addr  = io.settings.regenPhysicalAddress(s1_set_addr, evict_way_tag)

  val selected_way_OH = Mux(hit_valid, hit_way_sel, Mux(empty_valid, empty_way_sel, evict_way_sel)).toBools

  val md_update = s1_metadata.zip(selected_way_OH) map { case (md, sel) =>
    val next = WireInit(md)
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
  when (s1_valid) {
    md_array.write(s1_set_addr, VecInit(md_update))
  }

  // FIXME: Inner and outer widths are the same
  val block_beats = (1.U << (io.settings.blockBits - log2Ceil(nastiXDataBits/8).U))
  // AXI4 length; subtract 1
  val axi4_block_len = block_beats - 1.U

  val read_triggered_refill =  state === llc_r_mdaccess && !hit_valid
  val write_triggered_refill = state === llc_w_mdaccess && (writes.bits.len < axi4_block_len) &&
                               !hit_valid
  val need_refill = read_triggered_refill || write_triggered_refill
  val need_writeback = s1_valid && evict_dirty_way

  val allocate_mshr = need_refill || need_writeback

  when(allocate_mshr) {
    mshrs(mshr_next_idx) := MSHR(
      llcKey,
      xaction  = Mux(state === llc_r_mdaccess,
                    TransactionMetaData(reads.bits),
                    TransactionMetaData(writes.bits)),
      set_addr = s1_set_addr,
      do_acq   = need_refill,
      do_wb    = need_writeback)
  }


  // Refill Issue
  // For now always fetch whole cache lines from DRAM, even if fewer beats are required for 
  // a write-triggered refill
  val current_line_addr = io.settings.regenPhysicalAddress(s1_set_addr, s1_tag_addr)
  s2_ar_mem.io.enq.bits := NastiReadAddressChannel(
                            addr = current_line_addr,
                            id   = mshr_next_idx,
                            size = log2Ceil(nastiXDataBits/8).U,
                            len  = axi4_block_len)
  s2_ar_mem.io.enq.valid := need_refill

  reads.ready := (state === llc_r_mdaccess)

  // Writeback Issue
  s2_aw_mem.io.enq.bits := NastiWriteAddressChannel(
                            addr = dirty_line_addr,
                            id   = mshr_next_idx,
                            size = log2Ceil(nastiXDataBits/8).U,
                            len  = axi4_block_len)
  s2_aw_mem.io.enq.valid := need_writeback

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
  val can_refill = io.memRResp.valid &&
    (mshrs(io.memRResp.bits.id).xaction.isWrite || io.rResp.ready)
  io.memRResp.ready := refill_start

  // Data-array hazard tracking
  when (((state === llc_w_mdaccess || state === llc_r_mdaccess) && evict_dirty_way) ||
          refill_start) {
    d_array_busy.io.set.valid := true.B
    d_array_busy.io.set.bits  := axi4_block_len
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

  io.rResp.valid := (refill_start && !mshrs(io.memRResp.bits.id).xaction.isWrite) ||
                    (state === llc_r_mdaccess && hit_valid)
  io.rResp.bits := Mux(refill_start,
                       ReadResponseMetaData(mshrs(io.memRResp.bits.id).xaction),
                       ReadResponseMetaData(reads.bits))

  io.wResp.valid := (state === llc_w_mdaccess || state === llc_w_daccess) &&
    io.req.w.fire && io.req.w.bits.last
  io.wResp.bits := WriteResponseMetaData(writes.bits)

  switch (state) {
    is (llc_idle) {
      when (can_refill) {
        state := llc_refill
        refill_start := true.B
      }.elsewhen(can_deq_read) {
        state := llc_r_mdaccess
        read_start := true.B
      }.elsewhen(can_deq_write) {
        state := llc_w_mdaccess
        write_start := true.B
      }
    }
    is (llc_r_mdaccess) {
      when (hit_valid) {
        when(reads.bits.len =/= 0.U) {
          state := llc_r_daccess
        }.otherwise {
          state := llc_idle
        }
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

  // Instrumentation
  val miss_count = RegInit(0.U(32.W))
  when (s1_valid && !hit_valid) { miss_count := miss_count + 1.U }
  io.settings.misses := miss_count

  val wb_count = RegInit(0.U(32.W))
  when (s1_valid && evict_dirty_way) { wb_count := wb_count + 1.U }
  io.settings.writebacks := wb_count

  val refill_count = RegInit(0.U(32.W))
  when (state === llc_r_mdaccess && !hit_valid) { refill_count := refill_count + 1.U }
  io.settings.refills := refill_count

}
