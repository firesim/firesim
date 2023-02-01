// See LICENSE for license details.
package midas
package models

import firrtl.annotations.HasSerializationHints

// From RC
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.{DecoupledHelper}
import freechips.rocketchip.diplomacy.{IdRange, LazyModule, AddressSet, TransferSizes}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import junctions._

import chisel3._
import chisel3.util._

import midas.core._
import midas.widgets._

import scala.math.min
import Console.{UNDERLINED, RESET}


// Note: NASTI -> legacy rocket chip implementation of AXI4
case object FasedAXI4Edge extends Field[Option[AXI4EdgeSummary]](None)

case class BaseParams(
  // Pessimistically provisions the functional model. Don't be cheap:
  // underprovisioning will force functional model to assert backpressure on
  // target AW. W or R channels, which may lead to unexpected bandwidth throttling.
  maxReads: Int,
  maxWrites: Int,
  nastiKey: Option[NastiParameters] = None,
  edge: Option[AXI4EdgeParameters] = None,

  // If not providing an AXI4 edge, use these to constrain the amount of FPGA DRAM
  // used by the memory model
  targetAddressOffset: Option[BigInt]    = None,
  targetAddressSpaceSize: Option[BigInt] = None,

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

  // Number of xactions in flight in a given cycle. Bin N contains the range
  // (occupancyHistograms[N-1], occupancyHistograms[N]]
  occupancyHistograms: Seq[Int] = Seq(0, 2, 4, 8),
  addrRangeCounters: BigInt = BigInt(0)
)
// A serializable summary of the diplomatic edge
case class AXI4EdgeSummary(
  maxReadTransfer: Int,
  maxWriteTransfer: Int,
  idReuse: Option[Int],
  maxFlight: Option[Int],
  address: Seq[AddressSet]
) {
  def targetAddressOffset(): BigInt = address.map(_.base).min
}

object AXI4EdgeSummary {
  // Returns max ID reuse; None -> unbounded
  private def getIDReuseFromEdge(e: AXI4EdgeParameters): Option[Int] = {
    val maxFlightPerMaster = e.master.masters.map(_.maxFlight)
    maxFlightPerMaster.reduce( (_,_) match {
      case (Some(prev), Some(cur)) => Some(scala.math.max(prev, cur))
      case _ => None
    })
  }
  // Returns (maxReadLength, maxWriteLength)
  private def getMaxTransferFromEdge(e: AXI4EdgeParameters): (Int, Int) = {
    val beatBytes = e.slave.beatBytes
    val readXferSize  = e.slave.slaves.head.supportsRead.max
    val writeXferSize = e.slave.slaves.head.supportsWrite.max
    ((readXferSize + beatBytes - 1) / beatBytes, (writeXferSize + beatBytes - 1) / beatBytes)
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

  def apply(e: AXI4EdgeParameters, idx: Int = 0): AXI4EdgeSummary = {
    val slave = e.slave.slaves(idx)
    AXI4EdgeSummary(
    getMaxTransferFromEdge(e)._1,
    getMaxTransferFromEdge(e)._2,
    getIDReuseFromEdge(e),
    getMaxTotalFlightFromEdge(e),
    slave.address)
  }
}

abstract class BaseConfig {
  def params: BaseParams

  private def getMaxPerID(e: Option[AXI4EdgeSummary], modelMaxXactions: Int, userMax: Option[Int])(implicit p: Parameters): Int = {
    e.flatMap(_.idReuse).getOrElse(min(userMax.getOrElse(modelMaxXactions), modelMaxXactions))
  }

  def maxReadLength(implicit p: Parameters) = p(FasedAXI4Edge) match {
    case Some(e) => e.maxReadTransfer
    case _ => params.maxReadLength
  }

  def maxWriteLength(implicit p: Parameters) = p(FasedAXI4Edge) match {
    case Some(e) => e.maxWriteTransfer
    case _ => params.maxWriteLength
  }

  def maxWritesPerID(implicit p: Parameters) = getMaxPerID(p(FasedAXI4Edge), params.maxWrites, params.maxWritesPerID)
  def maxReadsPerID(implicit p: Parameters) = getMaxPerID(p(FasedAXI4Edge), params.maxReads, params.maxReadsPerID)

  def maxWrites(implicit p: Parameters) = {
    val maxFromEdge = p(FasedAXI4Edge).flatMap(_.maxFlight).getOrElse(params.maxWrites)
    min(params.maxWrites, maxFromEdge)
  }

  def maxReads(implicit p: Parameters) = {
    val maxFromEdge = p(FasedAXI4Edge).flatMap(_.maxFlight).getOrElse(params.maxReads)
    min(params.maxReads, maxFromEdge)
  }

  def useLLCModel = params.llcKey != None

  // Timing model classes implement this function to elaborate the correct module
  def elaborate()(implicit p: Parameters): TimingModel

  def maxWritesBits(implicit p: Parameters) = log2Up(maxWrites)
  def maxReadsBits(implicit p: Parameters) = log2Up(maxReads)

  def targetAddressSpace(implicit p: Parameters): Seq[AddressSet] =
    p(FasedAXI4Edge).map(_.address)
                    .getOrElse(AddressSet.misaligned(params.targetAddressOffset.getOrElse(0),
                                                     params.targetAddressSpaceSize.getOrElse(BigInt(1) << p(NastiKey).addrBits)))

  def targetWTransfer(implicit p: Parameters): TransferSizes =
    TransferSizes(1, maxWriteLength * p(NastiKey).dataBits/8)

  def targetRTransfer(implicit p: Parameters): TransferSizes =
    TransferSizes(1, maxReadLength * p(NastiKey).dataBits/8)
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

class FASEDTargetIO(implicit val p: Parameters) extends Bundle {
  val axi4 = Flipped(new NastiIO)
  val reset = Input(Bool())
  val clock = Input(Clock())
}

// Need to wrap up all the parameters in a case class for serialization. The edge and width
// were previously passed in via the target's Parameters object
case class CompleteConfig(
    userProvided: BaseConfig,
    axi4Widths: NastiParameters,
    axi4Edge: Option[AXI4EdgeSummary] = None,
    memoryRegionName: Option[String] = None) extends HasSerializationHints {
  def typeHints: Seq[Class[_]] = Seq(userProvided.getClass)
}

class FASEDMemoryTimingModel(completeConfig: CompleteConfig, hostParams: Parameters) extends BridgeModule[HostPortIO[FASEDTargetIO]]()(hostParams)
    with UsesHostDRAM {

  val cfg = completeConfig.userProvided

  // Reconstitute the parameters object
  implicit override val p = hostParams.alterPartial({
    case NastiKey => completeConfig.axi4Widths
    case FasedAXI4Edge => completeConfig.axi4Edge
  })

  val toHostDRAMNode = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "fased-memory-timing-model",
        id   = IdRange(0, 1 << p(NastiKey).idBits),
        aligned = true, // This must be the case for the TL-based width adapter to work 
        maxFlight = Some(math.max(cfg.maxReadsPerID, cfg.maxWritesPerID))
      )))))

  // Begin: Implementation of UsesHostDRAM
  val hostWidthBytes = p(MemNastiKey).dataBits / 8
  val targetWidthBytes = p(NastiKey).dataBits / 8
  val memoryMasterNode = if (hostWidthBytes == targetWidthBytes) {
    toHostDRAMNode
  } else {
    // Since there is no diplomatic AXI4 width converter, use the TL one
    val xbar = LazyModule(new TLXbar)
    val error = LazyModule(new TLError(DevNullParams(
        Seq(AddressSet(BigInt(1) << p(NastiKey).addrBits, 0xff)),
        maxAtomic = 1,
        maxTransfer = p(HostMemChannelKey).maxXferBytes),
      beatBytes = hostWidthBytes))

    (xbar.node
      := TLWidthWidget(targetWidthBytes)
      := TLFIFOFixer()
      := AXI4ToTL()
      := AXI4Buffer()
      := toHostDRAMNode )
    error.node := xbar.node
    (AXI4Buffer()
       := AXI4UserYanker()
       := TLToAXI4()
       := xbar.node)
  }

  val memorySlaveConstraints = MemorySlaveConstraints(cfg.targetAddressSpace, cfg.targetRTransfer, cfg.targetWTransfer)
  val memoryRegionName = completeConfig.memoryRegionName.getOrElse(getWName)
  // End: Implementation of UsesHostDRAM

  lazy val module = new Impl
  class Impl extends BridgeModuleImp(this) {
    val io = IO(new WidgetIO)
    val hPort = IO(HostPort(new FASEDTargetIO))
    val toHostDRAM: AXI4Bundle = toHostDRAMNode.out.head._1
    val tNasti = hPort.hBits.axi4
    val tReset = hPort.hBits.reset

    // Debug: Put an optional bound on the number of memory requests we can make
    // to the host memory system
    val funcModelRegs = Wire(new FuncModelProgrammableRegs)
    val ingress = Module(new IngressModule(cfg))

    val nastiToHostDRAM = Wire(new NastiIO)
    AXI4NastiAssigner.toAXI4(toHostDRAM, nastiToHostDRAM)
    nastiToHostDRAM.aw <> ingress.io.nastiOutputs.aw
    nastiToHostDRAM.ar <> ingress.io.nastiOutputs.ar
    nastiToHostDRAM.w  <> ingress.io.nastiOutputs.w

    val readEgress = Module(new ReadEgress(
      maxRequests = cfg.maxReads,
      maxReqLength = cfg.maxReadLength,
      maxReqsPerId = cfg.maxReadsPerID))

    readEgress.io.enq <> nastiToHostDRAM.r
    readEgress.io.enq.bits.user := DontCare

    val writeEgress = Module(new WriteEgress(
      maxRequests = cfg.maxWrites,
      maxReqLength = cfg.maxWriteLength,
      maxReqsPerId = cfg.maxWritesPerID))

    writeEgress.io.enq <> nastiToHostDRAM.b
    writeEgress.io.enq.bits.user := DontCare

    // Track outstanding requests to the host memory system
    val hOutstandingReads = SatUpDownCounter(cfg.maxReads)
    hOutstandingReads.inc := toHostDRAM.ar.fire
    hOutstandingReads.dec := toHostDRAM.r.fire && toHostDRAM.r.bits.last
    hOutstandingReads.max := cfg.maxReads.U
    val hOutstandingWrites = SatUpDownCounter(cfg.maxWrites)
    hOutstandingWrites.inc := toHostDRAM.aw.fire
    hOutstandingWrites.dec := toHostDRAM.b.fire
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
    // 5: If targetReset is asserted the host-memory system must first settle
    val tResetReady = (!tReset || host_mem_idle)

    // decoupled helper fire currently doesn't support directly passing true/false.B as exclude
    val tFireHelper = DecoupledHelper(hPort.toHost.hValid,
                                      hPort.fromHost.hReady,
                                      ingressReady, bReady, rReady, tResetReady)

    val targetFire = tFireHelper.fire()

    val gate = Module(new AbstractClockGate)
    gate.I := clock
    gate.CE := targetFire

    val model = withClock(gate.O)(cfg.elaborate())
    printGenerationConfig()

    // HACK: Feeding valid back on ready and ready back on valid until we figure out
    // channel tokenization
    hPort.toHost.hReady := tFireHelper.fire()
    hPort.fromHost.hValid := tFireHelper.fire()
    ingress.io.nastiInputs.hValid := tFireHelper.fire(ingressReady)

    model.tNasti <> tNasti
    model.reset := tReset
    // Connect up aw to ingress and model
    ingress.io.nastiInputs.hBits.aw.valid := tNasti.aw.fire
    ingress.io.nastiInputs.hBits.aw.bits := tNasti.aw.bits

    // Connect ar to ingress and model
    ingress.io.nastiInputs.hBits.ar.valid := tNasti.ar.fire
    ingress.io.nastiInputs.hBits.ar.bits := tNasti.ar.bits

    // Connect w to ingress and model
    ingress.io.nastiInputs.hBits.w.valid := tNasti.w.fire
    ingress.io.nastiInputs.hBits.w.bits := tNasti.w.bits

    // Connect target-level signals between egress and model
    readEgress.io.req.t := model.io.egressReq.r
    readEgress.io.req.hValid := targetFire
    readEgress.io.resp.tReady := model.io.egressResp.rReady
    model.io.egressResp.rBits := readEgress.io.resp.tBits

    writeEgress.io.req.t := model.io.egressReq.b
    writeEgress.io.req.hValid := targetFire
    writeEgress.io.resp.tReady := model.io.egressResp.bReady
    model.io.egressResp.bBits := writeEgress.io.resp.tBits

    ingress.reset     := reset.asBool || tReset && tFireHelper.fire(ingressReady)
    readEgress.reset  := reset.asBool || tReset && targetFire
    writeEgress.reset := reset.asBool || tReset && targetFire


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
      when(!(tResetReady && hPort.toHost.hValid && hPort.fromHost.hReady)) {
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
      attach(hostMemoryIdleCycles, "hostMemIdleCycles", ReadOnly)
      attach(hOutstandingWrites.value, "hostWritesOutstanding", ReadOnly)
      attach(hOutstandingReads.value, "hostReadsOutstanding", ReadOnly)
    }

    if (cfg.params.detectAddressCollisions) {
      val discardedMSBs = 6
      val collision_checker = Module(new AddressCollisionChecker(
        cfg.maxReads, cfg.maxWrites, p(NastiKey).addrBits - discardedMSBs))
      collision_checker.io.read_req.valid  := targetFire && tNasti.ar.fire
      collision_checker.io.read_req.bits   := tNasti.ar.bits.addr >> discardedMSBs
      collision_checker.io.read_done       := toHostDRAM.r.fire && toHostDRAM.r.bits.last

      collision_checker.io.write_req.valid := targetFire && tNasti.aw.fire
      collision_checker.io.write_req.bits  := tNasti.aw.bits.addr >> discardedMSBs
      collision_checker.io.write_done      := toHostDRAM.b.fire

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
        readEgress.io.enq.bits.id,
        cfg.maxReadsPerID
      )
      attachIO(hReadLatencyHist, "hostReadLatencyHist_")

      val hWriteLatencyHist = HostLatencyHistogram(
        ingress.io.nastiOutputs.aw.fire,
        ingress.io.nastiOutputs.aw.bits.id,
        writeEgress.io.enq.fire,
        writeEgress.io.enq.bits.id,
        cfg.maxWritesPerID
      )
      attachIO(hWriteLatencyHist, "hostWriteLatencyHist_")

      // target-time latencies of xactions
      val newTRead = RegInit(true.B)
      // Measure latency from reception of first read data beat; need
      // some state to track when a beat corresponds to the start of a new xaction
      when (targetFire) {
        when (model.tNasti.r.fire && model.tNasti.r.bits.last) {
          newTRead := true.B
        }.elsewhen (model.tNasti.r.fire) {
          newTRead := false.B
        }
      }

      val tReadLatencyHist = HostLatencyHistogram(
        model.tNasti.ar.fire && targetFire,
        model.tNasti.ar.bits.id,
        model.tNasti.r.fire && targetFire && newTRead,
        model.tNasti.r.bits.id,
        maxFlight = cfg.maxReadsPerID,
        cycleCountEnable = targetFire
      )
      attachIO(tReadLatencyHist, "targetReadLatencyHist_")

      val tWriteLatencyHist = HostLatencyHistogram(
        model.tNasti.aw.fire && targetFire,
        model.tNasti.aw.bits.id,
        model.tNasti.b.fire && targetFire,
        model.tNasti.b.bits.id,
        maxFlight = cfg.maxWritesPerID,
        cycleCountEnable = targetFire
      )
      attachIO(tWriteLatencyHist, "targetWriteLatencyHist_")

      // Total host-latency of transactions
      val totalReadLatencyHist = HostLatencyHistogram(
        model.tNasti.ar.fire && targetFire,
        model.tNasti.ar.bits.id,
        model.tNasti.r.fire && targetFire && newTRead,
        model.tNasti.r.bits.id,
        maxFlight = cfg.maxReadsPerID
      )
      attachIO(totalReadLatencyHist, "totalReadLatencyHist_")

      val totalWriteLatencyHist = HostLatencyHistogram(
        model.tNasti.aw.fire && targetFire,
        model.tNasti.aw.bits.id,
        model.tNasti.b.fire && targetFire,
        model.tNasti.b.bits.id,
        maxFlight = cfg.maxWritesPerID
      )
      attachIO(totalWriteLatencyHist, "totalWriteLatencyHist_")

      // Ingress latencies
      val iReadLatencyHist = HostLatencyHistogram(
        ingress.io.nastiInputs.hBits.ar.fire && targetFire,
        ingress.io.nastiInputs.hBits.ar.bits.id,
        ingress.io.nastiOutputs.ar.fire,
        ingress.io.nastiOutputs.ar.bits.id,
        maxFlight = cfg.maxReadsPerID,
      )
      attachIO(iReadLatencyHist, "ingressReadLatencyHist_")

      val iWriteLatencyHist = HostLatencyHistogram(
        ingress.io.nastiInputs.hBits.aw.fire && targetFire,
        ingress.io.nastiInputs.hBits.aw.bits.id,
        ingress.io.nastiOutputs.aw.fire,
        ingress.io.nastiOutputs.aw.bits.id,
        maxFlight = cfg.maxWritesPerID,
      )
      attachIO(iWriteLatencyHist, "ingressWriteLatencyHist_")
    }

    if (cfg.params.addrRangeCounters > 0) {
      val n = cfg.params.addrRangeCounters
      val readRanges = AddressRangeCounter(n, model.tNasti.ar, targetFire)
      val writeRanges = AddressRangeCounter(n, model.tNasti.aw, targetFire)
      val numRanges = n.U(32.W)

      attachIO(readRanges, "readRanges_")
      attachIO(writeRanges, "writeRanges_")
      attach(numRanges, "numRanges", ReadOnly)
    }

    val rrespError = RegEnable(toHostDRAM.r.bits.resp, 0.U,
      toHostDRAM.r.bits.resp =/= 0.U && toHostDRAM.r.fire)
    val brespError = RegEnable(toHostDRAM.r.bits.resp, 0.U,
      toHostDRAM.b.bits.resp =/= 0.U && toHostDRAM.b.fire)

    // Generate the configuration registers and tie them to the ctrl bus
    attachIO(model.io.mmReg)
    attachIO(funcModelRegs)
    attach(rrespError, "rrespError", ReadOnly, substruct = false)
    attach(brespError, "brespError", ReadOnly, substruct = false)

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      def genCPPmap(mapName: String, map: Map[String, BigInt]): String = {
        val prefix = s"const std::map<std::string, int> $mapName = {\n"
        map.foldLeft(prefix)((str, kvp) => str + s""" {\"${kvp._1}\", ${kvp._2}},\n""") + "};\n"
      }
      super.genHeader(base, memoryRegions, sb)
      sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_target_addr_bits", UInt32(p(NastiKey).addrBits)))
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

      println("\nDefault Settings")
      val functionalModelSettings = funcModelRegs.getDefaults()
      val timingModelSettings = model.io.mmReg.getDefaults()
      for ((key, value) <- functionalModelSettings ++ timingModelSettings) {
        println(s"  +mm_${key}${getWId}=${value}")
      }
      print("\n")
    }
  }
}

class FASEDBridge(argument: CompleteConfig)(implicit p: Parameters)
    extends BlackBox with Bridge[HostPortIO[FASEDTargetIO], FASEDMemoryTimingModel] {
  val io = IO(new FASEDTargetIO)
  val bridgeIO = HostPort(io)
  val constructorArg = Some(argument)
  generateAnnotations()
}

object FASEDBridge {
  def apply(clock: Clock, axi4: AXI4Bundle, reset: Bool, cfg: CompleteConfig)(implicit p: Parameters): FASEDBridge = {
    val ep = Module(new FASEDBridge(cfg)(p.alterPartial({ case NastiKey => cfg.axi4Widths })))
    ep.io.reset := reset
    ep.io.clock := clock
    // HACK: Nasti and Diplomatic have diverged to the point where it's no longer
    // safe to emit a partial connect leaf fields.
    AXI4NastiAssigner.toNasti(ep.io.axi4, axi4)
    //import chisel3.ExplicitCompileOptions.NotStrict
    //ep.io.axi4 <> axi4
    ep
  }
}
