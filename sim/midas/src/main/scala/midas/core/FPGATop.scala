// See LICENSE for license details.

package midas
package core

import junctions._
import midas.widgets._
import midas.passes.{HostClockSource}
import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._

import scala.collection.immutable.ListMap

/**
  * The following [[Field]]s capture the parameters of the four AXI4 bus types
  * presented to a simulator (in [[FPGATop]]). A [[PlatformShim]] is free to
  * adapt these widths, apply address offsets, etc...,  but the values set here
  * define what is used in metasimulation, since it treats
  * [[FPGATop]] as the root of the module hierarchy.
  */

/** CPU-managed AXI4, aka "pcis" on EC2 F1. Used by the CPU to do DMA into fabric-controlled memories.
  *  This could include in-fabric RAMs/FIFOs (for bridge streams) or (in the future) FPGA-attached DRAM channels.
  */
case object CPUManagedAXI4Key extends Field[Option[CPUManagedAXI4Params]]

/** FPGA-managed AXI4, aka "pcim" on F1. Used by the fabric to do DMA into
  * the host-CPU's memory. Used to implement bridge streams on platforms that lack a CPU-managed AXI4 interface.
  * Set this to None if this interface is not present on the host. 
  */
case object FPGAManagedAXI4Key extends Field[Option[FPGAManagedAXI4Params]]

// The AXI4 widths for a single host-DRAM channel
case object HostMemChannelKey extends Field[HostMemChannelParams]
// The number of host-DRAM channels -> all channels must have the same AXI4 widths
case object HostMemNumChannels extends Field[Int]
// See widgets/Widget.scala for CtrlNastiKey -> Configures the simulation control bus


/**
  * DRAM Allocation Knobs
  *
  * Constrains how much of memory controller's id space is used. If no
  * constraint is provided, the unified id space of all masters is presented
  * directly to each memory controller. If this id width exceeds that of the
  * controller, Golden Gate will throw an get an elaboration-time error
  * requesting a constraint. See [[AXI4IdSpaceConstraint]].
  */
case object HostMemIdSpaceKey extends Field[Option[AXI4IdSpaceConstraint]](None)

/**  Constrains how many id bits of the host memory channel are used, as well
  *  as how many requests are issued per id. This generates hardware
  *  proportional to (2^idBits) * maxFlight.
  *
  * @param idBits The number of lower idBits of the host memory channel to use.
  * @param maxFlight A bound on the number of requests the simulator will make per id.
  *
  */
case class AXI4IdSpaceConstraint(idBits: Int = 4, maxFlight: Int = 8)

// Legacy: the aggregate memory-space seen by masters wanting DRAM. Derived from HostMemChannelKey
case object MemNastiKey extends Field[NastiParameters]

/** Specifies the size and width of external memory ports */
case class HostMemChannelParams(
    size: BigInt,
    beatBytes: Int,
    idBits: Int,
    maxXferBytes: Int = 256) {
  def axi4BundleParams = AXI4BundleParameters(
    addrBits = log2Ceil(size),
    dataBits = 8 * beatBytes,
    idBits   = idBits)
}

/**
  * Specifies the AXI4 interface for FPGA-driven DMA
  *
  * @param size The size, in bytes, of the addressable region on the host CPU.
  * The addressable region is assumed to span [0, size). Host-specific offsets
  * should be handled by the FPGAShim.
  * @param dataBits The width of the interface in bits.
  * @param idBits The number of ID bits supported by the interface.
  * @param writeTransferSizes Supported write transfer sizes in bytes
  * @param readTransferSizes Supported read transfer sizes in bytes
  * @param interleavedId Set to indicate DMA responses may be interleaved.
  */
case class FPGAManagedAXI4Params(
    size: BigInt,
    dataBits: Int,
    idBits: Int,
    writeTransferSizes: TransferSizes,
    readTransferSizes: TransferSizes,
    interleavedId: Option[Int] = Some(0),
    ) {
  require(interleavedId == Some(0), "IdDeinterleaver not currently instantiated in FPGATop")
  require((isPow2(size)) && (size % 4096 == 0),
    "The size of the FPGA-managed DMA regions must be a power of 2, and larger than a page.")

  def axi4BundleParams = AXI4BundleParameters(
    addrBits = log2Ceil(size),
    dataBits = dataBits,
    idBits = idBits,
  )
}

case class CPUManagedAXI4Params(
    addrBits: Int,
    dataBits: Int,
    idBits: Int,
    maxFlight: Option[Int] = None,
  ) {
  def axi4BundleParams = AXI4BundleParameters(
    addrBits = addrBits,
    dataBits = dataBits,
    idBits = idBits,
  )
}

// Platform agnostic wrapper of the simulation models for FPGA
class FPGATop(implicit p: Parameters) extends LazyModule with HasWidgets {
  require(p(HostMemNumChannels) <= 4, "Midas-level simulation harnesses support up to 4 channels")
  require(p(CtrlNastiKey).dataBits == 32,
    "Simulation control bus must be 32-bits wide per AXI4-lite specification")
  val master = addWidget(new SimulationMaster)

  val bridgeAnnos = p(SimWrapperKey).annotations collect { case ba: BridgeIOAnnotation => ba }
  val bridgeModuleMap: ListMap[BridgeIOAnnotation, BridgeModule[_ <: Record with HasChannels]] = 
    ListMap((bridgeAnnos.map(anno => anno -> addWidget(anno.elaborateWidget))):_*)

  // Find all bridges that wish to be allocated FPGA DRAM, and group them
  // according to their memoryRegionName. Requested addresses will be unified
  // across a region allowing:
  // 1) Multiple bridges using the same name to share (and thus communicate through) DRAM
  // 2) Orthogonal address sets to be recombined into a contiguous one. Ex.
  //    When cacheline-striping a target's memory system across multiple FASED
  //    memory channels, it's useful to ee a single contiguous region of host
  //    memory that corresponds to the target's memory space.
  val bridgesRequiringDRAM = bridgeModuleMap.values.collect({ case b: UsesHostDRAM =>  b})
  val combinedRegions = bridgesRequiringDRAM.groupBy(_.memoryRegionName)
  val regionTuples = combinedRegions.values.map { bridgeSeq =>
    val unifiedAS = AddressSet.unify(bridgeSeq.flatMap(_.memorySlaveConstraints.address).toSeq)
    (bridgeSeq, unifiedAS)
  }

  // Tie-break with the name of the region.
  val sortedRegionTuples = regionTuples.toSeq.sortBy(r => (BytesOfDRAMRequired(r._2), r._1.head.memoryRegionName)).reverse

  // Allocate memory regions using a base-and-bounds scheme
  val dramOffsetsRev = sortedRegionTuples.foldLeft(Seq(BigInt(0)))({
    case (offsets, (bridgeSeq, addresses)) =>
      val requestedCapacity = BytesOfDRAMRequired(addresses)
      val pageAligned4k = ((requestedCapacity + 4095) >> 12) << 12
      (offsets.head + pageAligned4k) +: offsets
  })
  val totalDRAMAllocated = dramOffsetsRev.head
  val dramOffsets = dramOffsetsRev.tail.reverse
  val availableDRAM =  p(HostMemNumChannels) * p(HostMemChannelKey).size
  require(totalDRAMAllocated <= availableDRAM,
    s"Total requested DRAM of ${totalDRAMAllocated}B, exceeds host capacity of ${availableDRAM}B") 

  val loadMem = addWidget(new LoadMemWidget(totalDRAMAllocated))
  // Host DRAM handling
  val memChannelParams = p(HostMemChannelKey)
  // Define multiple single-channel nodes, instead of one multichannel node to more easily 
  // bind a subset to the XBAR.
  val memAXI4Nodes = Seq.tabulate(p(HostMemNumChannels)) { channel =>
    val device = new MemoryDevice
    val base = channel * memChannelParams.size
    AXI4SlaveNode(
      Seq(AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(AddressSet(base, memChannelParams.size - 1)),
          resources     = device.reg,
          regionType    = RegionType.UNCACHED, // cacheable
          executable    = false,
          supportsWrite = TransferSizes(1, memChannelParams.maxXferBytes),
          supportsRead  = TransferSizes(1, memChannelParams.maxXferBytes),
          interleavedId = Some(0))), // slave does not interleave read responses
        beatBytes = memChannelParams.beatBytes)
    ))
  }

  // In keeping with the Nasti implementation, we put all channels on a single XBar.
  val xbar = AXI4Xbar()

  private def bindActiveHostChannel(channelNode: AXI4SlaveNode): Unit = p(HostMemIdSpaceKey) match {
    case Some(AXI4IdSpaceConstraint(idBits, maxFlight)) =>
      (channelNode := AXI4Buffer()
                   := AXI4UserYanker(Some(maxFlight))
                   := AXI4IdIndexer(idBits)
                   := AXI4Buffer()
                   := xbar)
    case None =>
      (channelNode := AXI4Buffer()
                   := xbar)
  }

  // Connect only as many channels as needed by bridges requesting host DRAM.
  // Always connect one channel because:
  // 1) It is still assumed in some places, see loadmem
  // 2) Almost all simulators we've built to date require at least one channel
  // 3) In F1, the first DRAM channel cannot be omitted.
  val dramChannelsRequired = math.max(1, math.ceil(totalDRAMAllocated.toDouble / p(HostMemChannelKey).size.toLong).toInt)
  for ((node, idx) <- memAXI4Nodes.zipWithIndex) {
    if (idx < dramChannelsRequired) {
      bindActiveHostChannel(node)
    } else {
      node := AXI4TieOff()
    }
  }

  xbar := loadMem.toHostMemory
  val targetMemoryRegions = Map(sortedRegionTuples.zip(dramOffsets).map({ case ((bridgeSeq, addresses), hostBaseAddr) =>
    val regionName = bridgeSeq.head.memoryRegionName
    val virtualBaseAddr = addresses.map(_.base).min
    val offset = hostBaseAddr - virtualBaseAddr
    val preTranslationPort = (xbar
      :=* AXI4Buffer()
      :=* AXI4AddressTranslation(offset, addresses, regionName))
    bridgeSeq.foreach { bridge =>
      (preTranslationPort := AXI4Deinterleaver(bridge.memorySlaveConstraints.supportsRead.max)
                          := bridge.memoryMasterNode)
    }
    regionName -> offset
  }):_*)

  def printHostDRAMSummary(): Unit = {
    def toIECString(value: BigInt): String = {
      val dv = value.doubleValue
      if (dv >= 1e9) {
        f"${dv / (1024 * 1024 * 1024)}%.3f GiB"
      } else if (dv >= 1e6) {
        f"${dv / (1024 * 1024)}%.3f MiB"
      } else {
        f"${dv / 1024}%.3f KiB"
      }
    }
    println(s"Total Host-FPGA DRAM Allocated: ${toIECString(totalDRAMAllocated)} of ${toIECString(availableDRAM)} available.")

    if (sortedRegionTuples.nonEmpty) {
      println("Host-FPGA DRAM Allocation Map:")
    }

    sortedRegionTuples.zip(dramOffsets).foreach({ case ((bridgeSeq, addresses), offset) =>
      val regionName = bridgeSeq.head.memoryRegionName
      val bridgeNames = bridgeSeq.map(_.getWName).mkString(", ")
      println(f"  ${regionName} -> [0x${offset}%X, 0x${offset + BytesOfDRAMRequired(addresses) - 1}%X]")
      println(f"    Associated bridges: ${bridgeNames}")
    })
  }

  val bridgesWithToHostCPUStreams = bridgeModuleMap.values
    .collect { case b: StreamToHostCPU => b }
  val hasToHostStreams = bridgesWithToHostCPUStreams.nonEmpty

  val bridgesWithFromHostCPUStreams = bridgeModuleMap.values
    .collect { case b: StreamFromHostCPU => b }
  val hasFromHostCPUStreams = bridgesWithFromHostCPUStreams.nonEmpty

  def printStreamSummary(streams: Iterable[StreamParameters], header: String): Unit = {
    val summaries = streams.toList match {
      case Nil => "None" :: Nil
      case o => o.map { _.summaryString }
    }

    println((header +: summaries).mkString("\n  "))
  }

  val toCPUStreamParams = bridgesWithToHostCPUStreams.map { _.streamSourceParams }
  val fromCPUStreamParams = bridgesWithFromHostCPUStreams.map { _.streamSinkParams }

  val streamingEngine = addWidget(p(StreamEngineInstantiatorKey)(
    StreamEngineParameters(toCPUStreamParams.toSeq, fromCPUStreamParams.toSeq), p)
  )

  require(streamingEngine.fpgaManagedAXI4NodeOpt.isEmpty || p(FPGAManagedAXI4Key).nonEmpty,
    "Selected StreamEngine uses the FPGA-managed AXI4 interface but it is not available on this platform."
  )
  require(streamingEngine.cpuManagedAXI4NodeOpt.isEmpty || p(CPUManagedAXI4Key).nonEmpty,
    "Selected StreamEngine uses the CPU-managed AXI4 interface, but it is not available on this platform."
  )

  val cpuManagedAXI4NodeTuple =  p(CPUManagedAXI4Key).map { params =>
    val node = AXI4MasterNode(Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
          name       = "cpu-managed-axi4",
          id         = IdRange(0, 1 << params.idBits),
          aligned    = false,
          maxFlight  = params.maxFlight, // None = infinite, else is a per-ID cap
        ))
      )
    ))
    streamingEngine.cpuManagedAXI4NodeOpt.foreach {
      _ := AXI4Buffer() := node
    }
    (node, params)
  }

  val fpgaManagedAXI4NodeTuple = p(FPGAManagedAXI4Key).map { params =>
    val node = AXI4SlaveNode(
      Seq(AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(AddressSet(0, params.size - 1)),
          resources     = (new MemoryDevice).reg,
          regionType    = RegionType.UNCACHED, // cacheable
          executable    = false,
          supportsWrite = params.writeTransferSizes,
          supportsRead  = params.readTransferSizes,
          interleavedId = params.interleavedId)),
        beatBytes = params.dataBits / 8)
    ))

    streamingEngine.fpgaManagedAXI4NodeOpt match {
      case Some(engineNode) =>
        node := AXI4IdIndexer(params.idBits) := AXI4Buffer() := engineNode
      case None =>
        node := AXI4TieOff()
    }
    (node, params)
  }

  def genHeader(sb: StringBuilder): Unit = {
    super.genWidgetHeaders(sb, targetMemoryRegions)
  }

  lazy val module = new FPGATopImp(this)
}

class FPGATopImp(outer: FPGATop)(implicit p: Parameters) extends LazyModuleImp(outer) {
  // Mark the host clock so that ILA wiring and user-registered host
  // transformations can inject hardware synchronous to correct clock.
  HostClockSource.annotate(clock)

  val ctrl = IO(Flipped(WidgetMMIO()))
  val mem = IO(Vec(p(HostMemNumChannels), AXI4Bundle(p(HostMemChannelKey).axi4BundleParams)))

  val cpu_managed_axi4 = outer.cpuManagedAXI4NodeTuple.map { case (node, params) => 
    val port = IO(Flipped(AXI4Bundle(params.axi4BundleParams)))
    node.out.head._1 <> port
    port
  }

  val fpga_managed_axi4 = outer.fpgaManagedAXI4NodeTuple.map { case (node, params) =>
    val port = IO(AXI4Bundle(params.axi4BundleParams))
    port <> node.in.head._1
    port
  }
  // Hack: Don't touch the ports so that we can use FPGATop as top-level in ML simulation
  dontTouch(ctrl)
  dontTouch(mem)
  cpu_managed_axi4.foreach(dontTouch(_))
  fpga_managed_axi4.foreach(dontTouch(_))

  (mem zip outer.memAXI4Nodes.map(_.in.head)).foreach { case (io, (bundle, _)) =>
    require(bundle.params.idBits <= p(HostMemChannelKey).idBits,
      s"""| Required memory channel ID bits exceeds that present on host.
          | Required: ${bundle.params.idBits} Available: ${p(HostMemChannelKey).idBits}
          | Enable host ID reuse with the HostMemIdSpaceKey""".stripMargin)
    io <> bundle
  }

  val sim = Module(new SimWrapper(p(SimWrapperKey)))
  val simIo = sim.channelPorts

  // Instantiate bridge widgets.
  outer.bridgeModuleMap.map({ case (bridgeAnno, bridgeMod) =>
    val widgetChannelPrefix = s"${bridgeAnno.target.ref}"
    bridgeMod.module.hPort.connectChannels2Port(bridgeAnno, simIo)
  })

  outer.printStreamSummary(outer.toCPUStreamParams,   "Bridge Streams To CPU:")
  outer.printStreamSummary(outer.fromCPUStreamParams, "Bridge Streams From CPU:")

  for (((sink, src), idx) <- outer.streamingEngine.streamsToHostCPU.zip(outer.bridgesWithToHostCPUStreams).zipWithIndex) {
    val allocatedIdx = src.toHostStreamIdx
    require(allocatedIdx == idx,
      s"Allocated to-host stream index ${allocatedIdx} does not match stream vector index ${idx}.")
    sink <> src.streamEnq
  }

  for (((sink, src), idx) <- outer.bridgesWithFromHostCPUStreams.zip(outer.streamingEngine.streamsFromHostCPU).zipWithIndex) {
    val allocatedIdx = sink.fromHostStreamIdx
    require(allocatedIdx == idx,
      s"Allocated from-host stream index ${allocatedIdx} does not match stream vector index ${idx}.")
    sink.streamDeq <> src
  }

  outer.genCtrlIO(ctrl)
  outer.printMemoryMapSummary()
  outer.printHostDRAMSummary()

  val confCtrl = (ctrl.nastiXIdBits, ctrl.nastiXAddrBits, ctrl.nastiXDataBits)
  val memParams = p(HostMemChannelKey).axi4BundleParams
  val confMem = (memParams.idBits, memParams.addrBits, memParams.dataBits)
  val confCPUManaged = cpu_managed_axi4.map(m => (m.params.idBits, m.params.addrBits, m.params.dataBits))
  val confFPGAManaged = fpga_managed_axi4.map(m => (m.params.idBits, m.params.addrBits, m.params.dataBits))

  def genHeader(sb: StringBuilder, target: String)(implicit p: Parameters) = {
    outer.genHeader(sb)

    def printConfig(conf: (Int, Int, Int)) : Unit = {
      val (idBits, addrBits, dataBits) = conf
      sb.append("AXI4Config{")
      sb.append(s"${idBits}, ${addrBits}, ${dataBits}")
      sb.append("}")
    }

    sb.append("static constexpr TargetConfig conf_target{\n")
    sb.append(".ctrl = ")
    printConfig(confCtrl)

    sb.append(",\n.mem = ")
    printConfig(confMem)

    sb.append(s",\n.mem_num_channels = ${p(HostMemNumChannels)}")

    sb.append(",\n.cpu_managed = ")
    confCPUManaged match {
      case None => sb.append("std::nullopt")
      case Some(conf) => printConfig(conf)
    }
    sb.append(",\n.fpga_managed = ")
    confFPGAManaged match {
      case None => sb.append("std::nullopt")
      case Some(conf) => printConfig(conf)
    }
    sb.append(s",\n.target_name = ${CStrLit(target).toC}")
    sb.append("\n};\n")
  }

  def genVHeader(sb: StringBuilder)(implicit p: Parameters) = {
    sb.append("\n// Simulation Constants\n")

    def printMacro(prefix: String, name: String, value: Long): Unit = {
      sb.append(s"`define ${prefix}_${name} ${value}\n")
    }

    def printConfig(prefix: String, conf: (Int, Int, Int)) {
      val (idBits, addrBits, dataBits) = conf
      printMacro(prefix, "ID_BITS", idBits)
      printMacro(prefix, "ADDR_BITS", addrBits)
      printMacro(prefix, "DATA_BITS", dataBits)
    }

    printConfig("CTRL", confCtrl)
    confCPUManaged.foreach { conf =>
      printConfig("CPU_MANAGED_AXI4", conf)
      printMacro("CPU_MANAGED_AXI4", "PRESENT", 1.toLong)
    }
    confFPGAManaged.foreach { conf =>
      printConfig("FPGA_MANAGED_AXI4", conf)
      printMacro("FPGA_MANAGED_AXI4", "PRESENT", 1.toLong)
    }

    for (idx <- 1 to p(HostMemNumChannels)) {
      printMacro("MEM", s"HAS_CHANNEL${idx - 1}", 1.toLong)
    }
    printConfig("MEM", confMem)
  }
}
