package midas.models.sram

import chisel3._
import chisel3.util.DecoupledIO

object BankedAsyncMemChiselModel {
  /**
    * NOTE: Enabling read skips can change simulation results for buggy targets that use undefined read data
    */
  case class HostParams(maxPhysReads: Int, maxPhysWrites: Int, enableReadSkips: Boolean, enableWriteSkips: Boolean)
}

class BankedAsyncMemChiselModel(
  depth: Int,
  dataWidth: Int,
  nReads: Int,
  nWrites: Int,
  hostParams: BankedAsyncMemChiselModel.HostParams = BankedAsyncMemChiselModel.HostParams(4, 2, false, true)) extends Module {

  // Channelized IO
  val channels = IO(new RegfileModelIO(depth, dataWidth, nReads, nWrites))

  // Structures to organize read/write ops
  case class Read(cmd: DecoupledIO[ReadCmd], resp: DecoupledIO[UInt], done: Bool) {
    def fireable = cmd.valid && resp.ready && !done
    def skippable = fireable && !cmd.bits.en && hostParams.enableReadSkips.B
  }

  case class Write(cmd: DecoupledIO[WriteCmd], done: Bool) {
    def fireable = cmd.valid && !done
    def skippable = fireable && !(cmd.bits.en && cmd.bits.mask) && hostParams.enableWriteSkips.B
  }

  // Use up to two underlying banks of TDP BRAMs
  val nPhysReads = hostParams.maxPhysReads.min(nReads)
  val physReadCmds = Wire(Vec(nPhysReads, new ReadCmd(depth, dataWidth)))
  val physReadResps = Wire(Vec(nPhysReads, UInt(dataWidth.W)))

  // All writes (up to two) get broadcast to all banks
  val nPhysWrites = hostParams.maxPhysWrites.min(nWrites)
  val physWriteCmds = Wire(Vec(nPhysWrites, new WriteCmd(depth, dataWidth)))

  // Put each target read's metadata into a product for convenience
  val reads = (channels.read_cmds zip channels.read_resps).map {
    case (rc, rr) =>
      val rDone = RegInit(false.B)
      rDone.suggestName("rDone")
      Read(rc, rr, rDone)
  }

  // Divide target reads as evenly as possible into nPhysReads banks
  val rBankMaxSize = (channels.read_cmds.length + nPhysReads - 1) / nPhysReads

  // Pick the winning read for each read bank
  //   - Hook it up to the underlying memory's corresponding port
  //   - Mark it done
  (reads.grouped(rBankMaxSize).toSeq zip (physReadCmds zip physReadResps)).foreach {
    case (rBank, (underlyingRC, underlyingRR)) =>
      underlyingRC.en := true.B
      underlyingRC.addr := rBank.head.cmd.bits.addr
      val activeOH = chisel3.util.PriorityEncoderOH(rBank.map(_.fireable))
      (rBank zip activeOH).foreach {
        case (r, active) =>
          val active_piped = RegNext(active)
          r.cmd.ready := active
          r.resp.valid := active_piped
          r.resp.bits := underlyingRR
          when (active) {
            underlyingRC.addr := r.cmd.bits.addr
            r.done := true.B
          }
       }
  }

  // Writes can only begin once all reads are done
  val allReadsDone = reads.map(_.done).reduce(_ && _)

  // Put each target write's metadata into a product for convenience
  val writes = channels.write_cmds.map {
    case wc =>
      val wDone = RegInit(false.B)
      wDone.suggestName("wDone")
      Write(wc, wDone)
  }

  // Divide target writes as evenly as possible into nPhysWrites banks
  val wBankMaxSize = (channels.write_cmds.length + nPhysWrites - 1) / nPhysWrites

  // Pick the winning write for each write bank
  //   - Hook it up to the underlying memory's corresponding port
  //   - The target mask is effectively just part of the 'enable' -- handled by scheduler
  //   - Mark it done
  (writes.grouped(wBankMaxSize).toSeq zip physWriteCmds).foreach {
    case (wBank, underlyingWC) =>
      underlyingWC.en := false.B
      underlyingWC.mask := true.B
      underlyingWC.addr := wBank.head.cmd.bits.addr
      underlyingWC.data := wBank.head.cmd.bits.data
      val activeOH = chisel3.util.PriorityEncoderOH(wBank.map(w => w.fireable && !w.skippable && allReadsDone))
      (wBank zip activeOH).foreach {
        case (w, active) =>
          w.cmd.ready := active || w.skippable
          when (active || w.skippable) {
            w.done := true.B
          }
          when (active) {
            underlyingWC.addr := w.cmd.bits.addr
            underlyingWC.data := w.cmd.bits.data
            underlyingWC.en := true.B
          }
       }
  }

  val writesDoneOrFinishing = writes.map(w => w.done || w.cmd.ready).reduce(_ && _)

  when (allReadsDone && writesDoneOrFinishing) {
    reads.map(r => r.done := false.B)
    writes.map(w => w.done := false.B)
  }

  // Do the actual physical memory accesses
  (physReadCmds zip physReadResps).grouped(2).foreach {
    case physBank =>
      val bankMem = SyncReadMem(depth, UInt(dataWidth.W))
      val ports = (physBank.map(_._1.addr).padTo(nPhysWrites, 0.U) zip physWriteCmds.map(_.addr)).map { case (ra, wa) => bankMem(Mux(allReadsDone, wa, ra)) }
      (physBank zip ports).foreach { case ((_, rd), p) => rd := p }
      when (allReadsDone) {
        (physWriteCmds zip ports).foreach {
          case (wc, p) =>
            when (wc.en && wc.mask) {
              p := wc.data
            }
        }
      }
  }

  // No target reset
  channels.reset.ready := true.B
}
