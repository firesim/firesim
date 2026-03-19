package midas
package models

import Console.{RESET, UNDERLINED}

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util.{MaskGen, UIntToOH1}

// NOTE: This LLC model is *very* crude model of a cache that simple forwards
// misses onto the DRAM model, while short-circuiting hits.
import junctions._

import firesim.lib.nasti._

// State to track reads to DRAM, ~loosely an MSHR
class MSHR(nastiParams: NastiParameters, llcKey: LLCParams)(implicit p: Parameters) extends NastiBundle(nastiParams) {
  val set_addr      = UInt(llcKey.sets.maxBits.W)
  val xaction       = new TransactionMetaData(nastiParams)
  val wb_in_flight  = Bool()
  val acq_in_flight = Bool()
  val enabled       = Bool() // Set by a runtime configuration register

  def valid(): Bool     = (wb_in_flight || acq_in_flight) && enabled
  def available(): Bool = !valid() && enabled
  def setCollision(set_addr: UInt): Bool = (set_addr === this.set_addr) && valid()

  // Call on a MSHR register; sets all pertinent fields (leaving enabled untouched)
  def allocate(
    new_xaction:  TransactionMetaData,
    new_set_addr: UInt,
    do_acq:       Bool,
    do_wb:        Bool = false.B,
  )(implicit p:   Parameters
  ): Unit = {
    set_addr      := new_set_addr
    wb_in_flight  := do_wb
    acq_in_flight := do_acq
    xaction       := new_xaction
  }
}

object MSHR {
  def apply(nastiParams: NastiParameters, llcKey: LLCParams)(implicit p: Parameters): MSHR = {
    val w = Wire(new MSHR(nastiParams, llcKey))
    w.wb_in_flight  := false.B
    w.acq_in_flight := false.B
    // Initialize to enabled to play nice with assertions
    w.enabled       := true.B
    w.xaction       := DontCare
    w.set_addr      := DontCare
    w
  }
}

class BlockMetadata(tagBits: Int) extends Bundle {
  val tag   = UInt(tagBits.W)
  val valid = Bool()
  val dirty = Bool()
}

class LLCProgrammableSettings(llcKey: LLCParams) extends Bundle with HasProgrammableRegisters with HasConsoleUtils {
  val wayBits     = Input(UInt(log2Ceil(llcKey.ways.maxBits).W))
  val setBits     = Input(UInt(log2Ceil(llcKey.sets.maxBits).W))
  val blockBits   = Input(UInt(log2Ceil(llcKey.blockBytes.maxBits).W))
  val activeMSHRs = Input(UInt(log2Ceil(llcKey.mshrs.max + 1).W))

  // Instrumentation
  val misses        = Output(UInt(32.W))                             // Total accesses is provided by (totalReads + totalWrites)
  val writebacks    = Output(UInt(32.W))                             // Number of dirty lines returned to DRAM
  val refills       = Output(UInt(32.W))                             // Number of clean lines requested from DRAM
  val peakMSHRsUsed = Output(UInt(log2Ceil(llcKey.mshrs.max + 1).W)) // Peak number of MSHRs used
  // Note short-burst writes will produce a refill, whereas releases from caches will not

  val registers = Seq(
    wayBits     -> RuntimeSetting(llcKey.ways.maxBits, "Log2(ways per set)"),
    setBits     -> RuntimeSetting(llcKey.sets.maxBits, "Log2(sets per bank"),
    blockBits   -> RuntimeSetting(llcKey.blockBytes.maxBits, "Log2(cache-block bytes"),
    activeMSHRs -> RuntimeSetting(llcKey.mshrs.max, "Number of MSHRs", min = 1, max = Some(llcKey.mshrs.max)),
  )

  def maskTag(addr:                  UInt): UInt = (addr >> (blockBits +& setBits))
  def maskSet(addr:                  UInt): UInt = ((addr >> blockBits) & ((1.U << setBits) - 1.U))(llcKey.sets.maxBits - 1, 0)
  def regenPhysicalAddress(set_addr: UInt, tag_addr: UInt): UInt =
    (set_addr << (blockBits)) |
      (tag_addr << (blockBits +& setBits))

  def setLLCSettings(bytesPerBlock: Option[Int] = None): Unit = {
    Console.println(s"\n${UNDERLINED}Last-Level Cache Settings${RESET}")

    regMap(blockBits).set(
      log2Ceil(
        requestInput(
          "Block size in bytes",
          default = llcKey.blockBytes.max,
          min     = Some(llcKey.blockBytes.min),
          max     = Some(llcKey.blockBytes.max),
        )
      )
    )
    regMap(setBits).set(
      log2Ceil(
        requestInput(
          "Number of sets in LLC",
          default = llcKey.sets.max,
          min     = Some(llcKey.sets.min),
          max     = Some(llcKey.sets.max),
        )
      )
    )
    regMap(wayBits).set(
      log2Ceil(
        requestInput(
          "Set associativity",
          default = llcKey.ways.max,
          min     = Some(llcKey.ways.min),
          max     = Some(llcKey.ways.max),
        )
      )
    )
  }
}

case class WRange(min: Int, max: Int) {
  def minBits: Int = log2Ceil(min)
  def maxBits: Int = log2Ceil(max)
}

case class LLCParams(
  ways:       WRange = WRange(1, 8),
  sets:       WRange = WRange(32, 4096),
  blockBytes: WRange = WRange(8, 128),
  mshrs:      WRange = WRange(1, 8),// TODO: check against AXI ID width
  banks:      Int    = 1,           // Number of independent LLC banks (must be power of 2)
) {

  def maxTagBits(addrWidth: Int): Int = addrWidth - blockBytes.minBits - sets.minBits

  def print(): Unit = {
    println("  LLC Parameters:")
    println("    Sets:              " + sets)
    println("    Associativity:     " + ways)
    println("    Block Size (B):    " + blockBytes)
    println("    MSHRs:             " + mshrs)
    println("    Banks:             " + banks)
    println("    Replacement Policy: Random\n")
  }
}

class LLCModelIO(nastiParams: NastiParameters, val key: LLCParams)(implicit val p: Parameters) extends Bundle {
  val req      = Flipped(new NastiReqChannels(nastiParams))
  val wResp    = Decoupled(new WriteResponseMetaData(nastiParams))         // to backend
  val rResp    = Decoupled(new ReadResponseMetaData(nastiParams))
  val memReq   = new NastiReqChannels(nastiParams)                         // to backing DRAM model
  val memRResp = Flipped(Decoupled(new ReadResponseMetaData(nastiParams))) // from backing DRAM model
  val memWResp = Flipped(Decoupled(new WriteResponseMetaData(nastiParams)))

  // LLC runtime configuration
  val settings = new LLCProgrammableSettings(key)
}

class LLCModel(nastiParams: NastiParameters, cfg: BaseConfig)(implicit p: Parameters) extends NastiModule(nastiParams) {
  val llcKey = cfg.params.llcKey.get
  val io     = IO(new LLCModelIO(nastiParams, llcKey))

  require(log2Ceil(llcKey.mshrs.max) <= nastiXIdBits, "Can have at most one MSHR per AXI ID")

  val maxTagBits    = llcKey.maxTagBits(nastiXAddrBits)
  val way_addr_mask = Reverse(MaskGen((llcKey.ways.max - 1).U, io.settings.wayBits, llcKey.ways.max))

  // Rely on intialization of BRAM to 0 during programming to unset all valid bits the md_array
  val md_array     = SyncReadMem(llcKey.sets.max, Vec(llcKey.ways.max, new BlockMetadata(maxTagBits)))
  val d_array_busy = Module(new DownCounter(8))
  d_array_busy.io.set.valid := false.B
  d_array_busy.io.set.bits  := DontCare
  d_array_busy.io.decr      := false.B

  val mshr_mask_vec  = UIntToOH1(io.settings.activeMSHRs, llcKey.mshrs.max).asBools
  val mshrs          = RegInit(VecInit(Seq.fill(llcKey.mshrs.max)(MSHR(nastiParams, llcKey))))
  // Enable only active MSHRs as requested in the runtime configuration
  mshrs.zipWithIndex.foreach({ case (m, idx) => m.enabled := mshr_mask_vec(idx) })

  val mshr_available = mshrs.exists({ m: MSHR => m.available() })
  val mshr_next_idx  = mshrs.indexWhere({ m: MSHR => m.available() })

  val mshrs_allocated = mshrs.count({ m: MSHR => m.valid() })
  assert(
    (mshrs_allocated < RegNext(io.settings.activeMSHRs)) || !mshr_available,
    "Too many runtime MSHRs exposed given runtime programmable limit",
  )

  assert(
    (mshrs_allocated === RegNext(io.settings.activeMSHRs)) || mshr_available,
    "Too few runtime MSHRs exposed given runtime programmable limit.",
  )

  val s2_ar_mem            = Module(new Queue(new NastiReadAddressChannel(nastiParams), 2))
  val s2_aw_mem            = Module(new Queue(new NastiWriteAddressChannel(nastiParams), 2))
  val miss_resource_hazard = !mshr_available || !s2_aw_mem.io.enq.ready || !s2_ar_mem.io.enq.ready

  val reads              = Queue(io.req.ar)
  val read_set           = io.settings.maskSet(reads.bits.addr)
  val read_set_collision = mshrs.exists({ m: MSHR => m.setCollision(read_set) })
  val can_deq_read       = reads.valid && !read_set_collision && !miss_resource_hazard && io.rResp.ready

  val writes              = Queue(io.req.aw)
  val write_set           = io.settings.maskSet(writes.bits.addr)
  val write_set_collision = mshrs.exists({ m: MSHR => m.setCollision(write_set) })
  val can_deq_write       = writes.valid && !write_set_collision && !miss_resource_hazard && mshr_available && io.wResp.ready

  val llc_idle :: llc_r_mdaccess :: llc_r_wb :: llc_r_daccess :: llc_w_mdaccess :: llc_w_wb :: llc_w_daccess :: llc_refill :: Nil =
    Enum(8)

  val state        = RegInit(llc_idle)
  val refill_start = WireInit(false.B)
  val read_start   = WireInit(false.B)
  val write_start  = WireInit(false.B)

  val set_addr = Mux(write_start, write_set, read_set)
  val tag_addr = io.settings.maskTag(Mux(write_start, writes.bits.addr, reads.bits.addr))

  // S1 = Tag matches, replacement candidate selection, and replacement policy update
  val s1_tag_addr = RegNext(tag_addr)
  val s1_set_addr = RegNext(set_addr)
  val s1_valid    = state === llc_r_mdaccess || state === llc_w_mdaccess
  val s1_metadata = {
    import Chisel._
    md_array.read(set_addr, read_start || write_start)
  }

  def isHit(m: BlockMetadata): Bool = m.valid && (m.tag === s1_tag_addr)
  val hit_ways    = VecInit(s1_metadata.map(isHit)).asUInt & way_addr_mask
  val hit_way_sel = PriorityEncoderOH(hit_ways)
  val hit_valid   = hit_ways.orR

  def isEmptyWay(m: BlockMetadata): Bool = !m.valid
  val empty_ways    = VecInit(s1_metadata.map(isEmptyWay)).asUInt & way_addr_mask
  val empty_way_sel = PriorityEncoderOH(empty_ways)
  val empty_valid   = empty_ways.orR

  val fill_empty_way = !hit_valid && empty_valid

  val lsfr               = Chisel.LFSR16(true.B)
  val evict_way_sel      = UIntToOH(lsfr(llcKey.ways.maxBits - 1, 0) & ((1.U << io.settings.wayBits) - 1.U))
  val evict_way_is_dirty = (VecInit(s1_metadata.map(_.dirty)).asUInt & evict_way_sel).orR
  val evict_way_tag      = Mux1H(evict_way_sel, s1_metadata.map(_.tag))

  val do_evict        = !hit_valid && !empty_valid
  val evict_dirty_way = do_evict && evict_way_is_dirty
  val dirty_line_addr = io.settings.regenPhysicalAddress(s1_set_addr, evict_way_tag)

  val selected_way_OH = Mux(hit_valid, hit_way_sel, Mux(empty_valid, empty_way_sel, evict_way_sel)).asBools

  val md_update = s1_metadata.zip(selected_way_OH).map { case (md, sel) =>
    val next = WireInit(md)
    when(sel) {
      when(fill_empty_way) {
        next.valid := true.B
      }
      when(state === llc_w_mdaccess) {
        next.dirty := true.B
        // This also assumes that all md fields in invalid ways are initialized
        // to zero during programming. Otherwise we'd need to unset the dirty bit
        // on a compulsory miss
      }.elsewhen(state === llc_r_mdaccess && do_evict) {
        next.dirty := false.B
      }
      when(do_evict || fill_empty_way) {
        next.tag := s1_tag_addr
      }
    }
    next
  }
  when(s1_valid) {
    md_array.write(s1_set_addr, VecInit(md_update))
  }

  // FIXME: Inner and outer widths are the same
  val block_beats    = (1.U << (io.settings.blockBits - log2Ceil(nastiXDataBits / 8).U))
  // AXI4 length; subtract 1
  val axi4_block_len = block_beats - 1.U

  val read_triggered_refill  = state === llc_r_mdaccess && !hit_valid
  val write_triggered_refill = state === llc_w_mdaccess && (writes.bits.len < axi4_block_len) &&
    !hit_valid
  val need_refill            = read_triggered_refill || write_triggered_refill
  val need_writeback         = s1_valid && evict_dirty_way

  val allocate_mshr = need_refill || need_writeback

  when(allocate_mshr) {
    mshrs(mshr_next_idx).allocate(
      new_xaction  = Mux(
        state === llc_r_mdaccess,
        TransactionMetaData(nastiParams, reads.bits),
        TransactionMetaData(nastiParams, writes.bits),
      ),
      new_set_addr = s1_set_addr,
      do_acq       = need_refill,
      do_wb        = need_writeback,
    )
  }

  // Refill Issue
  // For now always fetch whole cache lines from DRAM, even if fewer beats are required for
  // a write-triggered refill
  val current_line_addr = io.settings.regenPhysicalAddress(s1_set_addr, s1_tag_addr)
  s2_ar_mem.io.enq.bits  := NastiReadAddressChannel(
    nastiParams,
    addr = current_line_addr,
    id   = mshr_next_idx,
    size = log2Ceil(nastiXDataBits / 8).U,
    len  = axi4_block_len,
  )
  s2_ar_mem.io.enq.valid := need_refill

  reads.ready := (state === llc_r_mdaccess)

  // Writeback Issue
  s2_aw_mem.io.enq.bits  := NastiWriteAddressChannel(
    nastiParams,
    addr = dirty_line_addr,
    id   = mshr_next_idx,
    size = log2Ceil(nastiXDataBits / 8).U,
    len  = axi4_block_len,
  )
  s2_aw_mem.io.enq.valid := need_writeback

  writes.ready := io.req.w.bits.last && io.req.w.fire

  io.memReq.ar          <> s2_ar_mem.io.deq
  io.memReq.aw          <> s2_aw_mem.io.deq
  io.memReq.w.valid     := (state === llc_r_wb || state === llc_w_wb)
  io.memReq.w.bits.last := d_array_busy.io.idle
  io.memReq.w.bits.user := 0.U
  io.memReq.w.bits.data := 0.U
  io.memReq.w.bits.id   := 0.U
  io.memReq.w.bits.strb := 0.U

  // Handle responses from DRAM
  when(io.memWResp.valid) {
    mshrs(io.memWResp.bits.id).wb_in_flight := false.B
  }
  io.memWResp.ready := true.B

  when(refill_start) {
    mshrs(io.memRResp.bits.id).acq_in_flight := false.B
  }
  val can_refill = io.memRResp.valid &&
    (mshrs(io.memRResp.bits.id).xaction.isWrite || io.rResp.ready)
  io.memRResp.ready := refill_start

  // Data-array hazard tracking
  when(
    ((state === llc_w_mdaccess || state === llc_r_mdaccess) && evict_dirty_way) ||
      refill_start
  ) {
    d_array_busy.io.set.valid := true.B
    d_array_busy.io.set.bits  := axi4_block_len
  }.elsewhen(state === llc_r_mdaccess && hit_valid) {
    d_array_busy.io.set.valid := true.B
    d_array_busy.io.set.bits  := reads.bits.len
  }.elsewhen(
    state === llc_w_mdaccess && (hit_valid || empty_valid) ||
      state === llc_w_wb && (io.memReq.w.fire && io.memReq.w.bits.last)
  ) {
    d_array_busy.io.set.valid := true.B
    d_array_busy.io.set.bits  := writes.bits.len
  }

  d_array_busy.io.decr := Mux(
    state === llc_w_wb || state === llc_r_wb,
    io.memReq.w.fire,
    Mux(state === llc_w_daccess, io.req.w.valid, true.B),
  )

  io.req.w.ready := (state === llc_w_daccess) || (state === llc_w_mdaccess && !evict_dirty_way)

  io.rResp.valid := (refill_start && !mshrs(io.memRResp.bits.id).xaction.isWrite) ||
    (state === llc_r_mdaccess && hit_valid)
  io.rResp.bits  := Mux(
    refill_start,
    ReadResponseMetaData(nastiParams, mshrs(io.memRResp.bits.id).xaction),
    ReadResponseMetaData(nastiParams, reads.bits),
  )

  io.wResp.valid := (state === llc_w_mdaccess || state === llc_w_daccess) &&
    io.req.w.fire && io.req.w.bits.last
  io.wResp.bits  := WriteResponseMetaData(nastiParams, writes.bits)

  switch(state) {
    is(llc_idle) {
      when(can_refill) {
        state        := llc_refill
        refill_start := true.B
      }.elsewhen(can_deq_read) {
        state      := llc_r_mdaccess
        read_start := true.B
      }.elsewhen(can_deq_write) {
        state       := llc_w_mdaccess
        write_start := true.B
      }
    }
    is(llc_r_mdaccess) {
      when(hit_valid) {
        when(reads.bits.len =/= 0.U) {
          state := llc_r_daccess
        }.otherwise {
          state := llc_idle
        }
      }.elsewhen(evict_dirty_way) {
        state := llc_r_wb
      }.otherwise {
        state := llc_idle
      }
    }
    is(llc_w_mdaccess) {
      when(!evict_dirty_way) {
        when(io.req.w.valid && io.req.w.bits.last) {
          state := llc_idle
        }.otherwise {
          state := llc_w_daccess
        }
      }.otherwise {
        state := llc_w_wb
      }
    }
    is(llc_r_wb) {
      when(io.memReq.w.fire && io.memReq.w.bits.last) {
        state := llc_idle
      }
    }
    is(llc_w_wb) {
      when(io.memReq.w.fire && io.memReq.w.bits.last) {
        state := llc_w_daccess
      }
    }
    is(llc_w_daccess) {
      when(io.req.w.valid && io.req.w.bits.last) {
        state := llc_idle
      }
    }
    is(llc_r_daccess) {
      when(d_array_busy.io.current === 1.U) {
        state := llc_idle
      }
    }
    is(llc_refill) {
      when(d_array_busy.io.current === 1.U) {
        state := llc_idle
      }
    }
  }

  // Instrumentation
  val miss_count = RegInit(0.U(32.W))
  when(s1_valid && !hit_valid) { miss_count := miss_count + 1.U }
  io.settings.misses := miss_count

  val wb_count = RegInit(0.U(32.W))
  when(s1_valid && evict_dirty_way) { wb_count := wb_count + 1.U }
  io.settings.writebacks := wb_count

  val refill_count = RegInit(0.U(32.W))
  when(state === llc_r_mdaccess && !hit_valid) { refill_count := refill_count + 1.U }
  io.settings.refills := refill_count

  val peak_mshrs_used = RegInit(0.U(log2Ceil(llcKey.mshrs.max + 1).W))
  when(peak_mshrs_used < mshrs_allocated) { peak_mshrs_used := mshrs_allocated }
  io.settings.peakMSHRsUsed := peak_mshrs_used

}

// Banked LLC model: wraps N independent LLCModel instances to eliminate
// head-of-line blocking across banks. Requests are routed to banks based
// on address bits; each bank has its own state machine, tag array, and MSHRs.
class BankedLLCModel(nastiParams: NastiParameters, cfg: BaseConfig, nBanks: Int)(implicit p: Parameters)
    extends NastiModule(nastiParams) {

  require(isPow2(nBanks) && nBanks >= 2, s"nBanks must be a power of 2 >= 2, got $nBanks")

  val llcKey   = cfg.params.llcKey.get
  val io       = IO(new LLCModelIO(nastiParams, llcKey))
  val bankBits = log2Ceil(nBanks)
  val mshrBits = log2Ceil(llcKey.mshrs.max)

  require(
    bankBits + mshrBits <= nastiXIdBits,
    s"Need ${bankBits + mshrBits} AXI ID bits (bank=$bankBits + mshr=$mshrBits) but only have $nastiXIdBits",
  )

  // Instantiate N independent LLC banks
  val banks = Seq.tabulate(nBanks) { i =>
    val bank = Module(new LLCModel(nastiParams, cfg))
    bank.suggestName(s"llc_bank_$i")
    bank
  }

  // Bank selection: lowest bits of cache-line index (addr >> blockBits)
  def bankSelect(addr: UInt): UInt =
    (addr >> io.settings.blockBits)(bankBits - 1, 0)

  // Wire shared runtime settings to all banks
  banks.foreach { bank =>
    bank.io.settings.wayBits     := io.settings.wayBits
    bank.io.settings.setBits     := io.settings.setBits
    bank.io.settings.blockBits   := io.settings.blockBits
    bank.io.settings.activeMSHRs := io.settings.activeMSHRs
  }

  // Aggregate instrumentation counters across banks
  io.settings.misses        := banks.map(_.io.settings.misses).reduce(_ +& _)
  io.settings.writebacks    := banks.map(_.io.settings.writebacks).reduce(_ +& _)
  io.settings.refills       := banks.map(_.io.settings.refills).reduce(_ +& _)
  io.settings.peakMSHRsUsed := banks.map(_.io.settings.peakMSHRsUsed).reduce(_ max _)

  // ========== Target-side request demux ==========

  // W bank-tracking FIFO: records which bank each AW was sent to,
  // so W data beats can be routed to the matching bank.
  val wBankFifo = Module(new Queue(UInt(bankBits.W), 2 * nBanks))

  // AR (read address) demux by address
  val readBankSel = bankSelect(io.req.ar.bits.addr)
  banks.zipWithIndex.foreach { case (bank, i) =>
    bank.io.req.ar.valid := io.req.ar.valid && readBankSel === i.U
    bank.io.req.ar.bits  := io.req.ar.bits
  }
  io.req.ar.ready := banks.zipWithIndex.map { case (bank, i) =>
    bank.io.req.ar.ready && readBankSel === i.U
  }.reduce(_ || _)

  // AW (write address) demux by address; gated on W FIFO having space
  val writeBankSel = bankSelect(io.req.aw.bits.addr)
  banks.zipWithIndex.foreach { case (bank, i) =>
    bank.io.req.aw.valid := io.req.aw.valid && writeBankSel === i.U && wBankFifo.io.enq.ready
    bank.io.req.aw.bits  := io.req.aw.bits
  }
  io.req.aw.ready := wBankFifo.io.enq.ready && banks.zipWithIndex.map { case (bank, i) =>
    bank.io.req.aw.ready && writeBankSel === i.U
  }.reduce(_ || _)

  wBankFifo.io.enq.valid := io.req.aw.fire
  wBankFifo.io.enq.bits  := writeBankSel

  // W (write data) routing: send beats to the bank at the FIFO head
  val currentWBank = wBankFifo.io.deq.bits
  val wFifoValid   = wBankFifo.io.deq.valid
  banks.zipWithIndex.foreach { case (bank, i) =>
    bank.io.req.w.valid := io.req.w.valid && wFifoValid && currentWBank === i.U
    bank.io.req.w.bits  := io.req.w.bits
  }
  io.req.w.ready         := wFifoValid && banks.zipWithIndex.map { case (bank, i) =>
    bank.io.req.w.ready && currentWBank === i.U
  }.reduce(_ || _)
  wBankFifo.io.deq.ready := io.req.w.fire && io.req.w.bits.last

  // ========== Target-side response mux ==========

  // 1-entry queues break the combinational loop between rResp.valid and rResp.ready.
  // Inside LLCModel, can_refill depends on io.rResp.ready, which feeds refill_start,
  // which feeds io.rResp.valid. The RRArbiter closes the loop (ready depends on valid).
  // The queue decouples them: bank sees queue.enq.ready = !full (independent of valid).
  val rRespArb = Module(new RRArbiter(new ReadResponseMetaData(nastiParams), nBanks))
  banks.zipWithIndex.foreach { case (bank, i) =>
    val q = Module(new Queue(new ReadResponseMetaData(nastiParams), 1))
    q.suggestName(s"rRespQueue_$i")
    q.io.enq <> bank.io.rResp
    rRespArb.io.in(i) <> q.io.deq
  }
  io.rResp <> rRespArb.io.out

  val wRespArb = Module(new RRArbiter(new WriteResponseMetaData(nastiParams), nBanks))
  banks.zipWithIndex.foreach { case (bank, i) => wRespArb.io.in(i) <> bank.io.wResp }
  io.wResp <> wRespArb.io.out

  // ========== Memory-side request mux ==========

  // Encode bank ID in upper bits of DRAM-side AXI ID: {bankId, mshrIdx}
  def encodeMemId(bankIdx: Int, origId: UInt): UInt = {
    if (mshrBits > 0) Cat(bankIdx.U(bankBits.W), origId(mshrBits - 1, 0))
    else bankIdx.U(bankBits.W)
  }

  // AR (read address) arbiter with ID rewriting
  val memArArb = Module(new RRArbiter(new NastiReadAddressChannel(nastiParams), nBanks))
  banks.zipWithIndex.foreach { case (bank, i) =>
    memArArb.io.in(i).valid := bank.io.memReq.ar.valid
    memArArb.io.in(i).bits  := bank.io.memReq.ar.bits
    memArArb.io.in(i).bits.id := encodeMemId(i, bank.io.memReq.ar.bits.id)
    bank.io.memReq.ar.ready := memArArb.io.in(i).ready
  }
  io.memReq.ar <> memArArb.io.out

  // AW (write address) arbiter with ID rewriting; gated on memory W FIFO space
  val memWBankFifo = Module(new Queue(UInt(bankBits.W), 2 * nBanks))

  val memAwArb = Module(new RRArbiter(new NastiWriteAddressChannel(nastiParams), nBanks))
  banks.zipWithIndex.foreach { case (bank, i) =>
    memAwArb.io.in(i).valid := bank.io.memReq.aw.valid
    memAwArb.io.in(i).bits  := bank.io.memReq.aw.bits
    memAwArb.io.in(i).bits.id := encodeMemId(i, bank.io.memReq.aw.bits.id)
    bank.io.memReq.aw.ready := memAwArb.io.in(i).ready && memWBankFifo.io.enq.ready
  }
  io.memReq.aw.valid       := memAwArb.io.out.valid && memWBankFifo.io.enq.ready
  io.memReq.aw.bits        := memAwArb.io.out.bits
  memAwArb.io.out.ready    := io.memReq.aw.ready && memWBankFifo.io.enq.ready
  memWBankFifo.io.enq.valid := io.memReq.aw.fire
  memWBankFifo.io.enq.bits  := memAwArb.io.chosen

  // W (write data) routing from banks to DRAM, ordered by AW grant sequence
  val currentMemWBank = memWBankFifo.io.deq.bits
  val memWFifoValid   = memWBankFifo.io.deq.valid
  io.memReq.w.valid := memWFifoValid && Mux1H(
    UIntToOH(currentMemWBank),
    banks.map(_.io.memReq.w.valid),
  )
  io.memReq.w.bits := Mux1H(
    UIntToOH(currentMemWBank),
    banks.map(_.io.memReq.w.bits),
  )
  banks.zipWithIndex.foreach { case (bank, i) =>
    bank.io.memReq.w.ready := io.memReq.w.ready && memWFifoValid && currentMemWBank === i.U
  }
  memWBankFifo.io.deq.ready := io.memReq.w.fire && io.memReq.w.bits.last

  // ========== Memory-side response demux ==========

  // Decode {bankId, mshrIdx} from DRAM response AXI IDs
  def decodeMemBankId(id: UInt): UInt = id >> mshrBits
  def decodeMemMshrId(id: UInt): UInt =
    if (mshrBits > 0) id(mshrBits - 1, 0) else 0.U

  // Read response demux
  val rRespBankId = decodeMemBankId(io.memRResp.bits.id)
  banks.zipWithIndex.foreach { case (bank, i) =>
    bank.io.memRResp.valid   := io.memRResp.valid && rRespBankId === i.U
    bank.io.memRResp.bits    := io.memRResp.bits
    bank.io.memRResp.bits.id := decodeMemMshrId(io.memRResp.bits.id)
  }
  io.memRResp.ready := banks.zipWithIndex.map { case (bank, i) =>
    bank.io.memRResp.ready && rRespBankId === i.U
  }.reduce(_ || _)

  // Write response demux
  val wRespBankId = decodeMemBankId(io.memWResp.bits.id)
  banks.zipWithIndex.foreach { case (bank, i) =>
    bank.io.memWResp.valid   := io.memWResp.valid && wRespBankId === i.U
    bank.io.memWResp.bits    := io.memWResp.bits
    bank.io.memWResp.bits.id := decodeMemMshrId(io.memWResp.bits.id)
  }
  io.memWResp.ready := banks.zipWithIndex.map { case (bank, i) =>
    bank.io.memWResp.ready && wRespBankId === i.U
  }.reduce(_ || _)
}
