// See LICENSE for license details.
package midas
package models

// From RC
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.{DecoupledHelper}
import freechips.rocketchip.diplomacy.{LazyModule}
import freechips.rocketchip.amba.axi4.{AXI4EdgeParameters}
import junctions._

import chisel3._
import chisel3.util._
import chisel3.experimental.dontTouch

import midas.core._
import midas.widgets._
import midas.passes.{Fame1ChiselAnnotation}

import scala.math.min
import Console.{UNDERLINED, RESET}

import java.io.{File, FileWriter}


case class BaseParams(
  // Pessimistically provisions the functional model. Don't be cheap:
  // underprovisioning will force functional model to assert backpressure on
  // target AW. W or R channels, which may lead to unexpected bandwidth throttling.
  maxReads: Int,
  maxWrites: Int,

  // AREA OPTIMIZATIONS:
  // AXI4 bursts(INCR) can be 256 beats in length -- some
  // area can be saved if the target design only issues smaller requests
  maxReadLength: Int = 256,
  maxReadsPerID: Option[Int] = None,
  maxWriteLength: Int = 256,
  maxWritesPerID: Option[Int] = None,

  // DEBUG FEATURES
  // Check for collisions in pending reads and writes to the host memory system
  // May produce false positives in timing models that reorder requests
  detectAddressCollisions: Boolean = false,

  // HOST INSTRUMENTATION
  stallEventCounters: Boolean = false, // To track causes of target-time stalls
  localHCycleCount: Boolean = false, // Host Cycle Counter
  latencyHistograms: Boolean = false, // Creates a BRAM histogram of various system latencies

  // BASE TIMING-MODEL SETTINGS
  // Some(key) instantiates an LLC model in front of the DRAM timing model
  llcKey: Option[LLCParams] = None,

  // BASE TIMING-MODEL INSTRUMENTATION
  xactionCounters: Boolean = true, // Numbers of read and write AXI4 xactions
  beatCounters: Boolean = false, // Numbers of read and write beats in AXI4 xactions
  targetCycleCounter: Boolean = false, // Redundant in a full simulator; useful for testing
  // Number of xactions in flight in a given cycle or Some(Number of Bins)
  occupancyHistograms: Option[Seq[(UInt) => Bool]] = Some(
    Seq({ _ === 0.U},
        { _ <  2.U},
        { _ <  4.U},
        { _ <  8.U},
        { x => true.B })
  ),

  addrRangeCounters: BigInt = BigInt(0)
)

abstract class BaseConfig(val params: BaseParams)(implicit p: Parameters) {

  // Returns (maxReadLength, maxWriteLength)
  private def getMaxTransferFromEdge(e: AXI4EdgeParameters): (Int, Int) = {
    val beatBytes = e.slave.beatBytes
    val readXferSize  = e.slave.slaves.head.supportsRead.max
    val writeXferSize = e.slave.slaves.head.supportsWrite.max
    ((readXferSize + beatBytes - 1) / beatBytes, (writeXferSize + beatBytes - 1) / beatBytes)
  }

  // Returns max ID reuse; None -> unbounded
  private def getIDReuseFromEdge(e: AXI4EdgeParameters): Option[Int] = {
    val maxFlightPerMaster = e.master.masters.map(_.maxFlight)
    maxFlightPerMaster.reduce( (_,_) match {
      case (Some(prev), Some(cur)) => Some(scala.math.max(prev, cur))
      case _ => None
    })
  }

  // Sums up the maximum number of requests that can be inflight across all masters
  // None -> unbounded
  private def getMaxTotalFlightFromEdge(e: AXI4EdgeParameters): Option[Int] = {
    val maxFlightPerMaster = e.master.masters.map(_.maxFlight)
    maxFlightPerMaster.reduce( (_,_) match {
      case (Some(prev), Some(cur)) => Some(prev + cur)
      case _ => None
    })
  }

  private def getMaxPerID(e: Option[AXI4EdgeParameters], modelMaxXactions: Int, userMax: Option[Int]): Int = {
    e.flatMap(getIDReuseFromEdge)
     .getOrElse(min(userMax.getOrElse(modelMaxXactions), modelMaxXactions))
  }

  def maxReadLength = p(FasedAXI4Edge) match {
    case Some(e) => getMaxTransferFromEdge(e)._1
    case _ => params.maxReadLength
  }

  def maxWriteLength = p(FasedAXI4Edge) match {
    case Some(e) => getMaxTransferFromEdge(e)._2
    case _ => params.maxWriteLength
  }

  def maxWritesPerID = getMaxPerID(p(FasedAXI4Edge), params.maxWrites, params.maxWritesPerID)
  def maxReadsPerID = getMaxPerID(p(FasedAXI4Edge), params.maxReads, params.maxReadsPerID)

  def maxWrites = {
    val maxFromEdge = p(FasedAXI4Edge).flatMap(getMaxTotalFlightFromEdge).getOrElse(params.maxWrites)
    min(params.maxWrites, maxFromEdge)
  }

  def maxReads = {
    val maxFromEdge = p(FasedAXI4Edge).flatMap(getMaxTotalFlightFromEdge).getOrElse(params.maxReads)
    min(params.maxReads, maxFromEdge)
  }

  def useLLCModel = params.llcKey != None

  // Timing model classes implement this function to elaborate the correct module
  def elaborate(): TimingModel

  def maxWritesBits = log2Up(maxWrites)
  def maxReadsBits = log2Up(maxReads)
}


// A wrapper bundle around all of the programmable settings in the functional model (!timing model).
class FuncModelProgrammableRegs extends Bundle with HasProgrammableRegisters {
  val relaxFunctionalModel = Input(Bool())

  val registers = Seq(
    (relaxFunctionalModel -> RuntimeSetting(0, """Relax functional model""", max = Some(1)))
  )

  def getFuncModelSettings(): Seq[(String, String)] = {
    Console.println(s"${UNDERLINED}Functional Model Settings${RESET}")
    setUnboundSettings()
    getSettings()
  }
}

class MemModelIO(implicit val p: Parameters) extends EndpointWidgetIO()(p){
  // The default NastiKey is expected to be that of the target
  val tNasti = Flipped(HostPort(new NastiIO, false))
  val host_mem = new NastiIO()(p.alterPartial({ case NastiKey => p(MemNastiKey)}))
  def hPort = tNasti
}

class FASEDMemoryTimingModel(cfg: BaseConfig)(implicit p: Parameters) extends EndpointWidget()(p) {
  require(p(NastiKey).idBits <= p(MemNastiKey).idBits,
    "Target AXI4 IDs cannot be mapped 1:1 onto host AXI4 IDs"
  )
  val io = IO(new MemModelIO)

  val model = cfg.elaborate()
  printGenerationConfig

  // Debug: Put an optional bound on the number of memory requests we can make
  // to the host memory system
  val funcModelRegs = Wire(new FuncModelProgrammableRegs)
  val ingress = Module(new IngressModule(cfg))

  // Drop in a width adapter to handle differences between
  // the host and target memory widths
  val widthAdapter = Module(LazyModule(
    new TargetToHostAXI4Converter(p(NastiKey), p(MemNastiKey))
  ).module)

  val hostMemOffsetWidthOffset = io.host_mem.aw.bits.addr.getWidth - p(CtrlNastiKey).dataBits 
  val hostMemOffsetLowWidth = if (hostMemOffsetWidthOffset > 0) p(CtrlNastiKey).dataBits else io.host_mem.aw.bits.addr.getWidth 
  val hostMemOffsetHighWidth = if (hostMemOffsetWidthOffset > 0) hostMemOffsetWidthOffset else 0 
  val hostMemOffsetHigh = RegInit(0.U(hostMemOffsetHighWidth.W))
  val hostMemOffsetLow = RegInit(0.U(hostMemOffsetLowWidth.W))
  val hostMemOffset = Cat(hostMemOffsetHigh, hostMemOffsetLow)
  attach(hostMemOffsetHigh, "hostMemOffsetHigh", WriteOnly)
  attach(hostMemOffsetLow, "hostMemOffsetLow", WriteOnly)

  io.host_mem <> widthAdapter.sAxi4
  io.host_mem.aw.bits.user := DontCare
  io.host_mem.aw.bits.region := DontCare
  io.host_mem.ar.bits.user := DontCare
  io.host_mem.ar.bits.region := DontCare
  io.host_mem.w.bits.id := DontCare
  io.host_mem.w.bits.user := DontCare
  io.host_mem.ar.bits.addr := widthAdapter.sAxi4.ar.bits.addr + hostMemOffset
  io.host_mem.aw.bits.addr := widthAdapter.sAxi4.aw.bits.addr + hostMemOffset

  widthAdapter.mAxi4.aw <> ingress.io.nastiOutputs.aw
  widthAdapter.mAxi4.ar <> ingress.io.nastiOutputs.ar
  widthAdapter.mAxi4.w <> ingress.io.nastiOutputs.w

  val readEgress = Module(new ReadEgress(
    maxRequests = cfg.maxReads,
    maxReqLength = cfg.maxReadLength,
    maxReqsPerId = cfg.maxReadsPerID))

  readEgress.io.enq <> widthAdapter.mAxi4.r
  readEgress.io.enq.bits.user := DontCare

  val writeEgress = Module(new WriteEgress(
    maxRequests = cfg.maxWrites,
    maxReqLength = cfg.maxWriteLength,
    maxReqsPerId = cfg.maxWritesPerID))

  writeEgress.io.enq <> widthAdapter.mAxi4.b
  writeEgress.io.enq.bits.user := DontCare

  // Track outstanding requests to the host memory system
  val hOutstandingReads = SatUpDownCounter(cfg.maxReads)
  hOutstandingReads.inc := io.host_mem.ar.fire()
  hOutstandingReads.dec := io.host_mem.r.fire() && io.host_mem.r.bits.last
  hOutstandingReads.max := cfg.maxReads.U
  val hOutstandingWrites = SatUpDownCounter(cfg.maxWrites)
  hOutstandingWrites.inc := io.host_mem.aw.fire()
  hOutstandingWrites.dec := io.host_mem.b.fire()
  hOutstandingWrites.max := cfg.maxWrites.U

  val host_mem_idle = hOutstandingReads.empty && hOutstandingWrites.empty
  // By default, disallow all R->W, W->R, and W->W reorderings in host memory
  // system. see IngressUnit.scala for more detail
  ingress.io.host_mem_idle := host_mem_idle
  ingress.io.host_read_inflight := !hOutstandingReads.empty
  ingress.io.relaxed := funcModelRegs.relaxFunctionalModel

  // Five conditions to execute a target cycle:
  // 1: AXI4 tokens are available, and there is space to enqueue a new input token
  // 2: Ingress has space for requests snooped in token
  val ingressReady = ingress.io.nastiInputs.hReady
  // 3: Egress unit has produced the payloads for read response channel
  val rReady = readEgress.io.resp.hValid
  // 4: Egress unit has produced the payloads for write response channel
  val bReady = writeEgress.io.resp.hValid
  // 5: We have a reset token, and if it's asserted the host-memory system must first settle
  val tResetReady = io.tReset.valid && (!io.tReset.bits || host_mem_idle)

  // decoupled helper fire currently doesn't support directly passing true/false.B as exclude
  val temp_true_B = true.B
  val tFireHelper = DecoupledHelper(io.tNasti.toHost.hValid,
                                    io.tNasti.fromHost.hReady,
                                    ingressReady, bReady, rReady, tResetReady, temp_true_B)

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
  readEgress.io.req.hValid := tFireHelper.fire(temp_true_B)
  readEgress.io.resp.tReady := model.io.egressResp.rReady
  model.io.egressResp.rBits := readEgress.io.resp.tBits

  writeEgress.io.req.t := model.io.egressReq.b
  writeEgress.io.req.hValid := tFireHelper.fire(temp_true_B)
  writeEgress.io.resp.tReady := model.io.egressResp.bReady
  model.io.egressResp.bBits := writeEgress.io.resp.tBits

  ingress.reset     := reset.toBool || io.tReset.bits && tFireHelper.fire(ingressReady)
  readEgress.reset  := reset.toBool || io.tReset.bits && tFireHelper.fire(temp_true_B)
  writeEgress.reset := reset.toBool || io.tReset.bits && tFireHelper.fire(temp_true_B)

  val targetFire = tFireHelper.fire(temp_true_B)// dummy arg

  if (cfg.params.localHCycleCount) {
    val hCycle = RegInit(0.U(32.W))
    hCycle := hCycle + 1.U
    attach(hCycle, "hostCycle", ReadOnly)
  }

  if (cfg.params.stallEventCounters) {
    val writeEgressStalls = RegInit(0.U(32.W))
    when(!bReady) {
      writeEgressStalls := writeEgressStalls + 1.U
    }

    val readEgressStalls = RegInit(0.U(32.W))
    when(!rReady) {
      readEgressStalls := readEgressStalls + 1.U
    }

    val tokenStalls = RegInit(0.U(32.W))
    when(!(tResetReady && io.tNasti.toHost.hValid && io.tNasti.fromHost.hReady)) {
      tokenStalls := tokenStalls + 1.U
    }

    val hostMemoryIdleCycles = RegInit(0.U(32.W))
    when(host_mem_idle) {
      hostMemoryIdleCycles := hostMemoryIdleCycles + 1.U
    }

    when (targetFire) {
      writeEgressStalls := 0.U
      readEgressStalls := 0.U
      tokenStalls := 0.U
    }
    attach(writeEgressStalls, "writeStalled", ReadOnly)
    attach(readEgressStalls, "readStalled", ReadOnly)
    attach(tokenStalls, "tokenStalled", ReadOnly)
  }

  if (cfg.params.detectAddressCollisions) {
    val discardedMSBs = 6
    val collision_checker = Module(new AddressCollisionChecker(
      cfg.maxReads, cfg.maxWrites, p(NastiKey).addrBits - discardedMSBs))
    collision_checker.io.read_req.valid  := targetFire && io.tNasti.hBits.ar.fire
    collision_checker.io.read_req.bits   := io.tNasti.hBits.ar.bits.addr >> discardedMSBs
    collision_checker.io.read_done       := io.host_mem.r.fire && io.host_mem.r.bits.last

    collision_checker.io.write_req.valid := targetFire && io.tNasti.hBits.aw.fire
    collision_checker.io.write_req.bits  := io.tNasti.hBits.aw.bits.addr >> discardedMSBs
    collision_checker.io.write_done      := io.host_mem.b.fire

    val collision_addr = RegEnable(collision_checker.io.collision_addr.bits,
                                   targetFire & collision_checker.io.collision_addr.valid)

    val num_collisions = RegInit(0.U(32.W))
    when (targetFire && collision_checker.io.collision_addr.valid) {
      num_collisions := num_collisions + 1.U
    }

    attach(num_collisions, "addrCollision", ReadOnly)
    attach(collision_addr, "collisionAddr", ReadOnly)
  }

  if (cfg.params.latencyHistograms) {

    // Measure latency from reception of first read data beat; need
    // some state to track when a beat corresponds to the start of a new xaction
    val newHRead = RegInit(true.B)
    when (readEgress.io.enq.fire && readEgress.io.enq.bits.last) {
      newHRead := true.B
    }.elsewhen (readEgress.io.enq.fire) {
      newHRead := false.B
    }
    // Latencies of host xactions
    val hReadLatencyHist = HostLatencyHistogram(
      ingress.io.nastiOutputs.ar.fire,
      ingress.io.nastiOutputs.ar.bits.id,
      readEgress.io.enq.fire && newHRead,
      readEgress.io.enq.bits.id
    )
    attachIO(hReadLatencyHist, "hostReadLatencyHist_")

    val hWriteLatencyHist = HostLatencyHistogram(
      ingress.io.nastiOutputs.aw.fire,
      ingress.io.nastiOutputs.aw.bits.id,
      writeEgress.io.enq.fire,
      writeEgress.io.enq.bits.id
    )
    attachIO(hWriteLatencyHist, "hostWriteLatencyHist_")

    // target-time latencies of xactions
    val newTRead = RegInit(true.B)
    // Measure latency from reception of first read data beat; need
    // some state to track when a beat corresponds to the start of a new xaction
    when (targetFire) {
      when (model.io.tNasti.r.fire && model.io.tNasti.r.bits.last) {
        newTRead := true.B
      }.elsewhen (model.io.tNasti.r.fire) {
        newTRead := false.B
      }
    }

    val tReadLatencyHist = HostLatencyHistogram(
      model.io.tNasti.ar.fire && targetFire,
      model.io.tNasti.ar.bits.id,
      model.io.tNasti.r.fire && targetFire && newTRead,
      model.io.tNasti.r.bits.id,
      cycleCountEnable = targetFire
    )
    attachIO(tReadLatencyHist, "targetReadLatencyHist_")

    val tWriteLatencyHist = HostLatencyHistogram(
      model.io.tNasti.aw.fire && targetFire,
      model.io.tNasti.aw.bits.id,
      model.io.tNasti.b.fire && targetFire,
      model.io.tNasti.b.bits.id,
      cycleCountEnable = targetFire
    )
    attachIO(tWriteLatencyHist, "targetWriteLatencyHist_")

    // Total host-latency of transactions
    val totalReadLatencyHist = HostLatencyHistogram(
      model.io.tNasti.ar.fire && targetFire,
      model.io.tNasti.ar.bits.id,
      model.io.tNasti.r.fire && targetFire && newTRead,
      model.io.tNasti.r.bits.id
    )
    attachIO(totalReadLatencyHist, "totalReadLatencyHist_")

    val totalWriteLatencyHist = HostLatencyHistogram(
      model.io.tNasti.aw.fire && targetFire,
      model.io.tNasti.aw.bits.id,
      model.io.tNasti.b.fire && targetFire,
      model.io.tNasti.b.bits.id
    )
    attachIO(totalWriteLatencyHist, "totalWriteLatencyHist_")

    // Ingress latencies
    val iReadLatencyHist = HostLatencyHistogram(
      ingress.io.nastiInputs.hBits.ar.fire() && targetFire,
      ingress.io.nastiInputs.hBits.ar.bits.id,
      ingress.io.nastiOutputs.ar.fire,
      ingress.io.nastiOutputs.ar.bits.id
    )
    attachIO(iReadLatencyHist, "ingressReadLatencyHist_")

    val iWriteLatencyHist = HostLatencyHistogram(
      ingress.io.nastiInputs.hBits.aw.fire() && targetFire,
      ingress.io.nastiInputs.hBits.aw.bits.id,
      ingress.io.nastiOutputs.aw.fire,
      ingress.io.nastiOutputs.aw.bits.id
    )
    attachIO(iWriteLatencyHist, "ingressWriteLatencyHist_")
  }

  if (cfg.params.addrRangeCounters > 0) {
    val n = cfg.params.addrRangeCounters
    val readRanges = AddressRangeCounter(n, model.io.tNasti.ar, targetFire)
    val writeRanges = AddressRangeCounter(n, model.io.tNasti.aw, targetFire)
    val numRanges = n.U(32.W)

    attachIO(readRanges, "readRanges_")
    attachIO(writeRanges, "writeRanges_")
    attach(numRanges, "numRanges", ReadOnly)
  }

  val rrespError = RegEnable(io.host_mem.r.bits.resp, 0.U,
    io.host_mem.r.bits.resp =/= 0.U && io.host_mem.r.fire)
  val brespError = RegEnable(io.host_mem.r.bits.resp, 0.U,
    io.host_mem.b.bits.resp =/= 0.U && io.host_mem.b.fire)

  // Generate the configuration registers and tie them to the ctrl bus
  attachIO(model.io.mmReg)
  attachIO(funcModelRegs)
  attach(rrespError, "rrespError", ReadOnly)
  attach(brespError, "brespError", ReadOnly)

  genCRFile()
  dontTouch(targetFire)
  chisel3.experimental.annotate(Fame1ChiselAnnotation(model, "targetFire"))
  getDefaultSettings("runtime.conf")

  override def genHeader(base: BigInt, sb: StringBuilder) {
    def genCPPmap(mapName: String, map: Map[String, BigInt]): String = {
      val prefix = s"const std::map<std::string, int> $mapName = {\n"
      map.foldLeft(prefix)((str, kvp) => str + s""" {\"${kvp._1}\", ${kvp._2}},\n""") + "};\n"
    }
    import midas.widgets.CppGenerationUtils._
    super.genHeader(base, sb)

    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_target_addr_bits", UInt32(p(NastiKey).addrBits)))

    crRegistry.genArrayHeader(wName.getOrElse(name).toUpperCase, base, sb)

    val targetAddrBits = model.io.tNasti.nastiXAddrBits
    sb.append(genMacro("TARGET_MEM_ADDR_BITS", UInt32(targetAddrBits)))
  }

  // Prints out key elaboration time settings
  private def printGenerationConfig(): Unit = {
    println("Generating a Midas Memory Model")
    println("  Max Read Requests: " + cfg.maxReads)
    println("  Max Write Requests: " + cfg.maxReads)
    println("  Max Read Length: " + cfg.maxReadLength)
    println("  Max Write Length: " + cfg.maxWriteLength)
    println("  Max Read ID Reuse: " + cfg.maxReadsPerID)
    println("  Max Write ID Reuse: " + cfg.maxWritesPerID)

    println("\nTiming Model Parameters")
    model.printGenerationConfig
    cfg.params.llcKey match {
      case Some(key) => key.print()
      case None => println("  No LLC Model Instantiated\n")
    }
  }

  // Accepts an elaborated memory model and generates a runtime configuration for it
  private def emitSettings(fileName: String, settings: Seq[(String, String)])(implicit p: Parameters): Unit = {
    val file = new File(p(OutputDir), fileName)
    val writer = new FileWriter(file)
    settings.foreach({
      case (field, value) => writer.write(s"+mm_${field}=${value}\n")
    })
    writer.close
  }

  def getSettings(fileName: String)(implicit p: Parameters) {
    println("\nGenerating a Midas Memory Model Configuration File")
    val functionalModelSettings = funcModelRegs.getFuncModelSettings()
    val timingModelSettings = model.io.mmReg.getTimingModelSettings()
    emitSettings(fileName, functionalModelSettings ++ timingModelSettings)
  }

  def getDefaultSettings(fileName: String)(implicit p: Parameters) {
    val functionalModelSettings = funcModelRegs.getDefaults()
    val timingModelSettings = model.io.mmReg.getDefaults()
    emitSettings(fileName, functionalModelSettings ++ timingModelSettings)
  }
}
