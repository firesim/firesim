package midas
package models

// From RC
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.{DecoupledHelper}
import junctions._

import chisel3._
import chisel3.util._

import midas.core._
import midas.widgets._
import midas.passes.{Fame1Annotator, DontTouchAnnotator}

import scala.math.min

case class BaseParams(
  maxReads: Int,
  maxWrites: Int,

  // Area Optimization: AXI4 bursts(INCR) can be 256 beats in length -- some
  // area can be saved if the target design only issues smaller requests
  maxReadLength: Int = 256,
  maxReadsPerID: Option[Int] = None,
  maxWriteLength: Int = 256,
  maxWritesPerID: Option[Int] = None)

abstract class BaseConfig(
  params: BaseParams
) extends MemModelConfig {

  def getMaxPerID(modelMaxXactions: Int, userMax: Option[Int]): Int = {
    min(userMax.getOrElse(modelMaxXactions), modelMaxXactions)
  }

  def maxWrites = params.maxWrites
  def maxReads = params.maxReads

  def maxReadLength = params.maxReadLength
  def maxWriteLength = params.maxWriteLength

  def numNastiIDs(implicit p: Parameters) = 1 << p(NastiKey).idBits

  def maxWritesPerID(implicit p: Parameters) =  getMaxPerID(maxWrites, params.maxWritesPerID)
  def maxReadsPerID(implicit p: Parameters) =  getMaxPerID(maxReads, params.maxReadsPerID)

  // Timing model classes implement this function to elaborate the correct module
  def elaborate()(implicit p: Parameters): TimingModel

  val maxWritesBits = log2Up(maxWrites)
  val maxReadsBits = log2Up(maxReads)
}

trait IngressModuleParameters {
  val cfg: BaseConfig
  // In general the only consequence of undersizing these are more wasted
  // host cycles the model waits to drain these. TODO: Find good defaults

  // DEADLOCK RISK: if the host memory system accepts only one AW while a W
  // xaction is inflight, and the entire W-transaction is not available in the
  // ingress module the host memory system will drain the WQueue without
  // consuming another AW token. The target will remain stalled and cannot
  // complete the W xaction.
  val ingressAWQdepth = cfg.maxWrites
  val ingressWQdepth = 2*cfg.maxWriteLength
  val ingressARQdepth = 4
}

// ********** IngressModule **********
// Simply queues up incoming target axi request channels.

class IngressModule(val cfg: BaseConfig)(implicit val p: Parameters) extends Module 
    with IngressModuleParameters {
  val io = IO(new Bundle {
    // This is target valid and not decoupled because the model has handshaked
    // the target-level channels already for us
    val nastiInputs = Flipped(HostDecoupled((new ValidNastiReqChannels)))
    val nastiOutputs = new NastiReqChannels
  })
  val awQueue = Module(new Queue(new NastiWriteAddressChannel, ingressAWQdepth))
  val wQueue  = Module(new Queue(new NastiWriteDataChannel, ingressWQdepth))
  val arQueue = Module(new Queue(new NastiReadAddressChannel, ingressARQdepth))

  val targetFire = io.nastiInputs.hReady && io.nastiInputs.hValid
  io.nastiInputs.hReady := awQueue.io.enq.ready && wQueue.io.enq.ready && arQueue.io.enq.ready

  arQueue.io.enq.bits := io.nastiInputs.hBits.ar.bits
  arQueue.io.enq.valid := targetFire && io.nastiInputs.hBits.ar.valid
  io.nastiOutputs.ar <> arQueue.io.deq

  // Host request gating -- wait until we have a complete W transaction before
  // we issue it.
  val wCredits = SatUpDownCounter(cfg.maxWrites)
  wCredits.inc := awQueue.io.enq.fire()
  wCredits.dec := wQueue.io.deq.fire() && wQueue.io.deq.bits.last
  val awCredits = SatUpDownCounter(cfg.maxWrites)
  awCredits.inc := wQueue.io.enq.fire() && wQueue.io.enq.bits.last
  awCredits.dec := awQueue.io.deq.fire()


  awQueue.io.enq.bits := io.nastiInputs.hBits.aw.bits
  awQueue.io.enq.valid := targetFire && io.nastiInputs.hBits.aw.valid
  wQueue.io.enq.bits := io.nastiInputs.hBits.w.bits
  wQueue.io.enq.valid := targetFire && io.nastiInputs.hBits.w.valid

  io.nastiOutputs.aw.bits := awQueue.io.deq.bits
  io.nastiOutputs.w.bits := wQueue.io.deq.bits

  io.nastiOutputs.aw.valid := ~awCredits.empty && awQueue.io.deq.valid
  awQueue.io.deq.ready := io.nastiOutputs.aw.ready && ~awCredits.empty
  io.nastiOutputs.w.valid := ~wCredits.empty && wQueue.io.deq.valid
  wQueue.io.deq.ready := io.nastiOutputs.w.ready && ~wCredits.empty
}

class MidasMemModel(cfg: BaseConfig)(implicit p: Parameters) extends MemModel
    with DontTouchAnnotator with Fame1Annotator {

  val model = cfg.elaborate()
  val ingress = Module(new IngressModule(cfg))
  io.host_mem.aw <> ingress.io.nastiOutputs.aw
  io.host_mem.ar <> ingress.io.nastiOutputs.ar
  io.host_mem.w  <> ingress.io.nastiOutputs.w

  val readEgress = Module(new ReadEgress(
    maxRequests = cfg.maxReads,
    maxReqLength = cfg.maxReadLength,
    maxReqsPerId = cfg.maxReadsPerID))

  readEgress.io.enq <> io.host_mem.r

  val writeEgress = Module(new WriteEgress(
    maxRequests = cfg.maxWrites,
    maxReqLength = cfg.maxWriteLength,
    maxReqsPerId = cfg.maxWritesPerID))

  writeEgress.io.enq <> io.host_mem.b

  // Track outstanding requests to the host memory system
  val hOutstandingReads = SatUpDownCounter(cfg.maxReads)
  hOutstandingReads.inc := io.host_mem.ar.fire()
  hOutstandingReads.dec := io.host_mem.r.fire() && io.host_mem.r.bits.last
  val hOutstandingWrites = SatUpDownCounter(cfg.maxWrites)
  hOutstandingWrites.inc := io.host_mem.aw.fire()
  hOutstandingWrites.dec := io.host_mem.b.fire()
  val hostMemoryIdle = hOutstandingReads.empty && hOutstandingWrites.empty

  // Five conditions to execute a target cycle:
  // 1: AXI4 tokens are available, and there is space to enqueue a new input token
  // 2: Ingress has space for requests snooped in token
  val ingressReady = ingress.io.nastiInputs.hReady
  // 3: Egress unit has produced the payloads for read response channel
  val rReady = readEgress.io.resp.hValid
  // 4: Egress unit has produced the payloads for write response channel
  val bReady = writeEgress.io.resp.hValid
  // 5: We have a reset token, and if it's asserted the host-memory system must first settle
  val tResetReady = io.tReset.valid && (!io.tReset.bits || hostMemoryIdle)

  val tFireHelper = DecoupledHelper(io.tNasti.toHost.hValid,
                                    io.tNasti.fromHost.hReady,
                                    ingressReady, bReady, rReady, tResetReady)

  io.tNasti.toHost.hReady := tFireHelper.fire(io.tNasti.toHost.hValid)
  io.tNasti.fromHost.hValid := tFireHelper.fire(io.tNasti.fromHost.hReady)
  io.tReset.ready := tFireHelper.fire(tResetReady)
  ingress.io.nastiInputs.hValid := tFireHelper.fire(ingressReady)

  model.io.tNasti <> io.tNasti.hBits
  model.reset := io.tReset.bits
  // Connect up aw to ingress and model
  ingress.io.nastiInputs.hBits.aw.valid := io.tNasti.hBits.aw.fire()
  ingress.io.nastiInputs.hBits.aw.bits := io.tNasti.hBits.aw.bits

  // Connect ar to ingress and model
  ingress.io.nastiInputs.hBits.ar.valid := io.tNasti.hBits.ar.fire()
  ingress.io.nastiInputs.hBits.ar.bits := io.tNasti.hBits.ar.bits

  // Connect w to ingress and model
  ingress.io.nastiInputs.hBits.w.valid := io.tNasti.hBits.w.fire()
  ingress.io.nastiInputs.hBits.w.bits := io.tNasti.hBits.w.bits

  // Connect target-level signals between egress and model
  readEgress.io.req.t := model.io.egressReq.r
  readEgress.io.req.hValid := tFireHelper.fire(true.B)
  readEgress.io.resp.tReady := model.io.egressResp.rReady
  model.io.egressResp.rBits := readEgress.io.resp.tBits

  writeEgress.io.req.t := model.io.egressReq.b
  writeEgress.io.req.hValid := tFireHelper.fire(true.B)
  writeEgress.io.resp.tReady := model.io.egressResp.bReady
  model.io.egressResp.bBits := writeEgress.io.resp.tBits

  ingress.reset := reset.toBool || io.tReset.bits && tFireHelper.fire(ingressReady)
  readEgress.reset := reset.toBool || io.tReset.bits && tFireHelper.fire(true.B)
  writeEgress.reset := reset.toBool || io.tReset.bits && tFireHelper.fire(true.B)

  val targetFire = tFireHelper.fire(true.B)// dummy arg
  // Generate the configuration registers and tie them to the ctrl bus
  attachIO(model.io.mmReg)
  genCRFile()
  dontTouch(targetFire)
  fame1transform(model)

  override def genHeader(base: BigInt, sb: StringBuilder) {
    def genCPPmap(mapName: String, map: Map[String, BigInt]): String = {
      val prefix = s"const std::map<std::string, int> $mapName = {\n"
      map.foldLeft(prefix)((str, kvp) => str + s""" {\"${kvp._1}\", ${kvp._2}},\n""") + "};\n"
    }
    import midas.widgets.CppGenerationUtils._
    super.genHeader(base, sb)

    crRegistry.genArrayHeader(wName.getOrElse(name).toUpperCase, base, sb)
  }
}
