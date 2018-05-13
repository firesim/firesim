package firesim
package endpoints

import chisel3.core._
import chisel3.util._
import DataMirror.directionOf
import freechips.rocketchip.config.Parameters

import midas.core._
import midas.widgets._
import testchipip.{BlockDeviceIO, BlockDeviceRequest, BlockDeviceData, BlockDeviceInfo, HasBlockDeviceParameters, BlockDeviceKey}

class SimBlockDev extends Endpoint {
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
  val dataBeats = (dataBytes * 8) / dataBitsPerBeat
  val sectorSize = log2Ceil(sectorBits/8)
  val beatIdxBits = log2Ceil(dataBeats)
  val pAddrBits = 32 // TODO: Make configurable somehow

  val io = IO(new BlockDevWidgetIO)

  val reqBuf = Module(new Queue(new BlockDeviceRequest, 10))
  val dataBuf = Module(new Queue(new BlockDeviceData, 32))
  val respBuf = Module(new Queue(new BlockDeviceData, 32))

  val target = io.hPort.hBits
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid
  val targetReset = tFire & io.tReset.bits

  reqBuf.reset  := reset.toBool || targetReset
  dataBuf.reset  := reset.toBool || targetReset
  respBuf.reset  := reset.toBool || targetReset

  io.hPort.toHost.hReady := tFire
  io.hPort.fromHost.hValid := tFire
  io.tReset.ready := tFire

  reqBuf.io.enq.bits := target.req.bits
  reqBuf.io.enq.valid := target.req.valid && tFire && !io.tReset.bits
  target.req.ready := reqBuf.io.enq.ready

  dataBuf.io.enq.bits := target.data.bits
  dataBuf.io.enq.valid := target.data.valid && tFire && !io.tReset.bits
  target.data.ready := dataBuf.io.enq.ready

  target.resp.bits := respBuf.io.deq.bits
  target.resp.valid := respBuf.io.deq.valid
  respBuf.io.deq.ready := target.resp.ready && tFire

  val nsectorReg = Reg(UInt(sectorBits.W))
  val max_req_lenReg = Reg(UInt(sectorBits.W))
  attach(nsectorReg, "bdev_nsectors", WriteOnly)
  attach(max_req_lenReg, "bdev_max_req_len", WriteOnly)
  target.info.nsectors := nsectorReg
  target.info.max_req_len := max_req_lenReg

  // req
  genROReg(reqBuf.io.deq.valid, "bdev_req_valid")
  genROReg(reqBuf.io.deq.bits.write, "bdev_req_write")
  genROReg(reqBuf.io.deq.bits.offset, "bdev_req_offset")
  genROReg(reqBuf.io.deq.bits.len, "bdev_req_len")
  genROReg(reqBuf.io.deq.bits.tag, "bdev_req_tag")
  Pulsify(genWORegInit(reqBuf.io.deq.ready, "bdev_req_ready", false.B), pulseLength = 1)

  // data
  genROReg(dataBuf.io.deq.valid, "bdev_data_valid")
  genROReg(dataBuf.io.deq.bits.data(63, 32), "bdev_data_data_upper")
  genROReg(dataBuf.io.deq.bits.data(31, 0), "bdev_data_data_lower")
  genROReg(dataBuf.io.deq.bits.tag, "bdev_data_tag")
  Pulsify(genWORegInit(dataBuf.io.deq.ready, "bdev_data_ready", false.B), pulseLength = 1)

  // resp
  val respDataRegUpper = Reg(UInt((dataBitsPerBeat/2).W))
  val respDataRegLower = Reg(UInt((dataBitsPerBeat/2).W))
  val respTag = Reg(UInt(tagBits.W))
  respBuf.io.enq.bits.data := Cat(respDataRegUpper, respDataRegLower)
  respBuf.io.enq.bits.tag := respTag
  attach(respDataRegUpper, "bdev_resp_data_upper", WriteOnly)
  attach(respDataRegLower, "bdev_resp_data_lower", WriteOnly)
  attach(respTag, "bdev_resp_tag", WriteOnly)
  Pulsify(genWORegInit(respBuf.io.enq.valid, "bdev_resp_valid", false.B), pulseLength = 1)
  genROReg(respBuf.io.enq.ready, "bdev_resp_ready")

  genROReg(!tFire, "done")

  genCRFile()
}
