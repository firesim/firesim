package firesim
package endpoints

import chisel3.core._
import chisel3.util._
import DataMirror.directionOf
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.DecoupledHelper

import midas.core._
import midas.widgets._
import midas.models.DynamicLatencyPipe
import testchipip.{BlockDeviceIO, BlockDeviceRequest, BlockDeviceData, BlockDeviceInfo, HasBlockDeviceParameters, BlockDeviceKey}

class SimBlockDev(
    override val clockRatio: IsRationalClockRatio = UnityClockRatio)
  extends Endpoint {
  def matchType(data: Data) = data match {
    case channel: BlockDeviceIO =>
      directionOf(channel.req.valid) == ActualDirection.Output
    case _ => false
  }
  def widget(p: Parameters) = new BlockDevWidget()(p)
  override def widgetName = "BlockDevWidget"
}

class BlockDevWidgetIO(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new BlockDeviceIO))
  val dma = None
  val address = None
}

class BlockDevWidget(implicit p: Parameters) extends EndpointWidget()(p) {
  // TODO use HasBlockDeviceParameters
  val blockDevExternal = p(BlockDeviceKey)
  val dataBytes = 512
  val sectorBits = 32
  val nTrackers = blockDevExternal.nTrackers
  val tagBits = log2Up(nTrackers)
  val nTrackerBits = log2Up(nTrackers+1)
  val dataBitsPerBeat = 64
  val dataBeats = (dataBytes * 8) / dataBitsPerBeat // A transaction is thus dataBeats * len beats long
  val sectorSize = log2Ceil(sectorBits/8)
  val beatIdxBits = log2Ceil(dataBeats)
  val pAddrBits = 32 // TODO: Make configurable somehow
  // Timing parameters
  val latencyBits = 24
  val defaultReadLatency = (1 << 8).U(latencyBits.W)
  val defaultWriteLatency = (1 << 8).U(latencyBits.W)

  val io = IO(new BlockDevWidgetIO)

  val reqBuf = Module(new Queue(new BlockDeviceRequest, 10))
  val dataBuf = Module(new Queue(new BlockDeviceData, 32))

  val rRespBuf = Module(new Queue(new BlockDeviceData, 32))
  val wAckBuf = Module(new Queue(UInt(tagBits.W), 4))

  val target = io.hPort.hBits
  val channelCtrlSignals = Seq(io.hPort.toHost.hValid,
                               io.hPort.fromHost.hReady,
                               io.tReset.valid)
  val rRespStallN = Wire(Bool()) // Unset if the SW model hasn't returned the response data in time
  val wAckStallN = Wire(Bool())  // As above, but with a write acknowledgement
  val fixMeOnNextRocketBump = true.B
  val tFireHelper = DecoupledHelper((channelCtrlSignals ++ Seq(
                                     reqBuf.io.enq.ready,
                                     dataBuf.io.enq.ready,
                                     fixMeOnNextRocketBump,
                                     rRespStallN,
                                     wAckStallN)):_*)

  val tFire = tFireHelper.fire(fixMeOnNextRocketBump) // Dummy argument to get conjunction of all signals
  // Decoupled helper can't exclude two bools unfortunately...
  val targetReset = channelCtrlSignals.reduce(_ && _) && io.tReset.bits

  reqBuf.reset  := reset.toBool || targetReset
  dataBuf.reset  := reset.toBool || targetReset
  rRespBuf.reset  := reset.toBool || targetReset
  wAckBuf.reset  := reset.toBool || targetReset

  io.hPort.toHost.hReady := tFireHelper.fire(io.hPort.toHost.hValid)
  io.hPort.fromHost.hValid := tFireHelper.fire(io.hPort.fromHost.hReady)
  io.tReset.ready := tFireHelper.fire(io.tReset.valid)

  reqBuf.io.enq.bits := target.req.bits
  reqBuf.io.enq.valid := target.req.valid && tFireHelper.fire(reqBuf.io.enq.ready)
  target.req.ready := true.B

  dataBuf.io.enq.bits := target.data.bits
  dataBuf.io.enq.valid := target.data.valid && tFireHelper.fire(dataBuf.io.enq.ready)
  target.data.ready := true.B

  // Begin Timing model
  val tCycle = RegInit(0.U(latencyBits.W))
  when (tFire) {
    tCycle := tCycle + 1.U
  }

  // Timing model programmable settings
  val readLatency = genWORegInit(Wire(UInt(latencyBits.W)), "read_latency", defaultReadLatency)
  val writeLatency = genWORegInit(Wire(UInt(latencyBits.W)), "write_latency", defaultWriteLatency)

  chisel3.experimental.withReset(reset.toBool || targetReset) {
    when (tFire) {
      assert(!target.req.fire || ((dataBeats.U * target.req.bits.len) < ((BigInt(1) << sectorBits) - 1).U),
             "Transaction length exceeds timing model maximum supported length")
    }

    // Timing Model -- Write Latency Pipe
    // Write latency = write-ack cycle - cycle to receive last write beat
    // Count the beats received for each tracker; they can be interleaved

    val wBeatCounters = Reg(Vec(nTrackers, UInt(sectorBits.W)))
    val wValid = RegInit(VecInit(Seq.fill(nTrackers)(false.B)))

    val writeLatencyPipe = Module(new DynamicLatencyPipe(UInt(1.W), nTrackers, latencyBits))
    writeLatencyPipe.io.enq.valid := false.B
    writeLatencyPipe.io.enq.bits := DontCare
    writeLatencyPipe.io.latency := writeLatency
    writeLatencyPipe.io.tCycle := tCycle

    val tagMatch = target.data.bits.tag === target.req.bits.tag

    wValid.zip(wBeatCounters).zipWithIndex.foreach { case ((valid, count), idx) =>
      val wReqFire  = target.req.fire && target.req.bits.write && target.req.bits.tag === idx.U
      val wDataFire = target.data.fire && target.data.bits.tag === idx.U
      val wDone = (wDataFire && count === 1.U)

      when (tFire) {
        // New write request received
        when(wDone) {
          assert(valid, "Write data received for unallocated tracker: %d\n", idx.U)
          writeLatencyPipe.io.enq.valid := true.B
          valid := false.B
        }.elsewhen (wReqFire) {
          valid := true.B
          count := Mux(wDataFire, dataBeats.U * target.req.bits.len - 1.U, dataBeats.U * target.req.bits.len)
          // We don't honestly expect len > 2^29 do we..
        // New data beat received for our tracker
        }.elsewhen (wDataFire) {
          count := count - 1.U
          assert(valid, "Write data received for unallocated tracker: %d\n", idx.U)
        }
      }
    }

    // Timing Model -- Read Latency Pipe
    // Read latency is simply the number of cycles between read-req and first resp beat
    val readLatencyPipe = Module(new DynamicLatencyPipe(UInt(sectorBits.W), nTrackers, latencyBits))

    readLatencyPipe.io.enq.valid := tFire && target.req.fire && !target.req.bits.write
    readLatencyPipe.io.enq.bits := target.req.bits.len
    readLatencyPipe.io.tCycle := tCycle
    readLatencyPipe.io.latency := readLatency

    // Scheduler. Prioritize returning write acknowledgements over returning read resps
    // as they are only a single cycle long
    val readRespBeatsLeft = RegInit(0.U(sectorBits.W))
    val returnWrite = RegInit(false.B)
    val readRespBusy = readRespBeatsLeft =/= 0.U
    val done = (returnWrite || readRespBeatsLeft === 1.U) && target.resp.fire
    val idle = !returnWrite && !readRespBusy
    writeLatencyPipe.io.deq.ready := false.B
    readLatencyPipe.io.deq.ready  := false.B

    when (tFire) {
      when (done || idle) {
        // If a write-response is waiting, return it first
        when(writeLatencyPipe.io.deq.valid) {
          returnWrite := true.B
          writeLatencyPipe.io.deq.ready := true.B
        }.elsewhen(readLatencyPipe.io.deq.valid) {
          readRespBeatsLeft := readLatencyPipe.io.deq.bits * dataBeats.U
          readLatencyPipe.io.deq.ready := true.B
        }.otherwise {
          readRespBeatsLeft := 0.U
          returnWrite := false.B
        }
      }.elsewhen(readRespBusy && target.resp.fire) {
        readRespBeatsLeft := readRespBeatsLeft - 1.U
      }
    }

    // Tie functional queues to output, gated with timing model control
    target.resp.valid  := !idle
    target.resp.bits.data  := 0.U
    target.resp.bits.tag  := 0.U
    // This shouldn't be necessary for a well behaved target, but only drive bits
    // through when there is valid data in the queue, closing a potential
    // determinism hole
    when (rRespBuf.io.deq.valid && readRespBusy) {
      target.resp.bits.data  := rRespBuf.io.deq.bits.data
      target.resp.bits.tag  := rRespBuf.io.deq.bits.tag
    }.elsewhen (wAckBuf.io.deq.valid && returnWrite) {
      target.resp.bits.tag  := wAckBuf.io.deq.bits
    }

    wAckStallN := !returnWrite || wAckBuf.io.deq.valid
    rRespStallN := !readRespBusy || rRespBuf.io.deq.valid

    wAckBuf.io.deq.ready := tFireHelper.fire(wAckStallN) && returnWrite && target.resp.ready
    rRespBuf.io.deq.ready := tFireHelper.fire(rRespStallN) && readRespBusy && target.resp.ready
  } // withReset{}

  // Memory mapped registers
  val nsectorReg = Reg(UInt(sectorBits.W))
  val max_req_lenReg = Reg(UInt(sectorBits.W))
  attach(nsectorReg, "bdev_nsectors", WriteOnly)
  attach(max_req_lenReg, "bdev_max_req_len", WriteOnly)
  target.info.nsectors := nsectorReg
  target.info.max_req_len := max_req_lenReg

  // Functional request queue (to CPU)
  genROReg(reqBuf.io.deq.valid, "bdev_req_valid")
  genROReg(reqBuf.io.deq.bits.write, "bdev_req_write")
  genROReg(reqBuf.io.deq.bits.offset, "bdev_req_offset")
  genROReg(reqBuf.io.deq.bits.len, "bdev_req_len")
  genROReg(reqBuf.io.deq.bits.tag, "bdev_req_tag")
  Pulsify(genWORegInit(reqBuf.io.deq.ready, "bdev_req_ready", false.B), pulseLength = 1)

  // Functional data queue (to CPU)
  genROReg(dataBuf.io.deq.valid, "bdev_data_valid")
  genROReg(dataBuf.io.deq.bits.data(63, 32), "bdev_data_data_upper")
  genROReg(dataBuf.io.deq.bits.data(31, 0), "bdev_data_data_lower")
  genROReg(dataBuf.io.deq.bits.tag, "bdev_data_tag")
  Pulsify(genWORegInit(dataBuf.io.deq.ready, "bdev_data_ready", false.B), pulseLength = 1)

  // Read reponse buffer MMIO IF (from CPU)
  val rRespDataRegUpper = genWOReg(Wire(UInt((dataBitsPerBeat/2).W)),"bdev_rresp_data_upper")
  val rRespDataRegLower = genWOReg(Wire(UInt((dataBitsPerBeat/2).W)),"bdev_rresp_data_lower")
  val rRespTag          = genWOReg(Wire(UInt(tagBits.W)            ),"bdev_rresp_tag")
  Pulsify(                genWORegInit(rRespBuf.io.enq.valid  ,"bdev_rresp_valid", false.B), pulseLength = 1)
  genROReg(rRespBuf.io.enq.ready, "bdev_rresp_ready")

  rRespBuf.io.enq.bits.data := Cat(rRespDataRegUpper, rRespDataRegLower)
  rRespBuf.io.enq.bits.tag := rRespTag

  // Write acknowledgement buffer MMIO IF (from CPU) -- we only need the tag from SW
  val wAckTag          = genWOReg(Wire(UInt(tagBits.W))            ,"bdev_wack_tag")
  Pulsify(               genWORegInit(wAckBuf.io.enq.valid   ,"bdev_wack_valid", false.B), pulseLength = 1)
  genROReg(wAckBuf.io.enq.ready, "bdev_wack_ready")
  wAckBuf.io.enq.bits := wAckTag

  // Indicates to the CPU-hosted component that we need to be serviced
  genROReg(reqBuf.io.deq.valid || dataBuf.io.deq.valid, "bdev_reqs_pending")
  genROReg(~wAckStallN, "bdev_wack_stalled")
  genROReg(~rRespStallN, "bdev_rresp_stalled")

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_latency_bits", UInt32(latencyBits)))
    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_num_trackers", UInt32(nTrackers)))
  }
}
