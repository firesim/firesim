// See LICENSE for license details.

package midas
package core

import junctions._
import midas.widgets._
import midas.passes.{HostClockSource}
import chisel3._
import chisel3.util._
import chisel3.experimental.{annotate, ChiselAnnotation}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.{DecoupledHelper, HeterogeneousBag}

import scala.collection.immutable.ListMap
import scala.collection.mutable
import CppGenerationUtils._

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

  val hashMaster: Option[TokenHashMaster] = if(p(InsertTokenHashersKey)) {
    Some(addWidget(new TokenHashMaster))
  } else {
    None
  }
  // val hashMaster: Option[TokenHashMaster] = None

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
  val targetMemoryRegions = sortedRegionTuples.zip(dramOffsets).map({ case ((bridgeSeq, addresses), hostBaseAddr) =>
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
    HostMemoryMapping(regionName, offset)
  })

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

    streamingEngine.fpgaManagedAXI4NodeOpt.foreach {
      node := AXI4IdIndexer(params.idBits) := AXI4Buffer() := _
    }
    (node, params)
  }

  override def genHeader(sb: StringBuilder): Unit = {
    super.genHeader(sb)
    targetMemoryRegions.foreach(_.serializeToHeader(sb))
  }

  lazy val module = new FPGATopImp(this)
}

class FPGATopImp(outer: FPGATop)(implicit p: Parameters) extends LazyModuleImp(outer) {

  def insertTokenHashers(hashSb: StringBuilder): Unit = {
    hashSb.append(genConstStatic("TOKENHASH_COUNT", UInt32(hashName.length)))

    hashSb.append(
      genArray(
        "TOKENHASH_BRIDGENAMES",
        hashBridgeName.map(CStrLit(_))
      )
    )

    hashSb.append(
      genArray(
        "TOKENHASH_NAMES",
        hashName.map(CStrLit(_))
      )
    )

    hashSb.append(
      genArray(
        "TOKENHASH_OUTPUTS",
        hashOutput.map(UInt32(_))
      )
    )

    hashSb.append(
      genArray(
        "TOKENHASH_QUEUEHEADS",
        hashQueueHead.map(UInt32(_))
      )
    )

    hashSb.append(
      genArray(
        "TOKENHASH_QUEUEOCCUPANCIES",
        hashQueueOccupancy.map(UInt32(_))
      )
    )

    hashSb.append(
      genArray(
        "TOKENHASH_TOKENCOUNTS0",
        hashtokenCount0.map(UInt32(_))
      )
    )

    hashSb.append(
      genArray(
        "TOKENHASH_TOKENCOUNTS1",
        hashtokenCount1.map(UInt32(_))
      )
    )

    Unit
  }

  // Mark the host clock so that ILA wiring and user-registered host
  // transformations can inject hardware synchronous to correct clock.
  HostClockSource.annotate(clock)

  val master  = outer.master
  val hashMaster  = outer.hashMaster

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

  val hashSb: StringBuilder = new StringBuilder()
  val hashBridgeName     = mutable.ArrayBuffer[String]()
  val hashName           = mutable.ArrayBuffer[String]()
  val hashOutput         = mutable.ArrayBuffer[Int]()
  val hashQueueHead      = mutable.ArrayBuffer[Int]()
  val hashQueueOccupancy = mutable.ArrayBuffer[Int]()
  val hashtokenCount0    = mutable.ArrayBuffer[Int]()
  val hashtokenCount1    = mutable.ArrayBuffer[Int]()

  // Instantiate bridge widgets.
  outer.bridgeModuleMap.map({ case (bridgeAnno, bridgeMod) =>
    val widgetChannelPrefix = s"${bridgeAnno.target.ref}"
    bridgeMod match {
      case peekPoke: PeekPokeBridgeModule =>
        peekPoke.module.io.step <> master.module.io.step
        master.module.io.done := peekPoke.module.io.idle
      case _ =>
    }
    bridgeMod.module.hPort.connectChannels2Port(bridgeAnno, simIo)


    val base = outer.getBaseAddr(bridgeMod)

    for (meta <- bridgeMod.module.hashRecord) {
      val mOffset = meta.offset(base)
      hashBridgeName += mOffset.bridgeName
      hashName += mOffset.name
      if(mOffset.output) {
        hashOutput += 1
       } else {
        hashOutput += 0
      }
      
      hashQueueHead += mOffset.queueHead.toInt
      hashQueueOccupancy += mOffset.queueOccupancy.toInt
      hashtokenCount0 += mOffset.tokenCount0.toInt
      hashtokenCount1 += mOffset.tokenCount1.toInt
    }

    hashMaster match {
      case Some(hm) => {
        // Connect "hasher config io" from master to all bridges
        bridgeMod.module.tokenHasherControlIO.triggerDelay := hm.module.io.triggerDelay
        bridgeMod.module.tokenHasherControlIO.triggerPeriod := hm.module.io.triggerPeriod
      }
      case None => {}
    }

  })

  hashMaster match {
    case Some(hm) => insertTokenHashers(hashSb)
    case None => {}
  }

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
  outer.emitDefaultPlusArgsFile()

  val headerConsts = List[(String, Long)](
    "CTRL_ID_BITS"   -> ctrl.nastiXIdBits,
    "CTRL_ADDR_BITS" -> ctrl.nastiXAddrBits,
    "CTRL_DATA_BITS" -> ctrl.nastiXDataBits,
    "CTRL_STRB_BITS" -> ctrl.nastiWStrobeBits,
    "CTRL_BEAT_BYTES"-> ctrl.nastiWStrobeBits,
    "CTRL_AXI4_SIZE" -> log2Ceil(ctrl.nastiWStrobeBits),
    // These specify channel widths; used mostly in the test harnesses
    "MEM_NUM_CHANNELS" -> p(HostMemNumChannels),
    "MEM_ADDR_BITS"  -> p(HostMemChannelKey).axi4BundleParams.addrBits,
    "MEM_DATA_BITS"  -> p(HostMemChannelKey).axi4BundleParams.dataBits,
    "MEM_ID_BITS"    -> p(HostMemChannelKey).axi4BundleParams.idBits,
    "MEM_STRB_BITS"  -> p(HostMemChannelKey).axi4BundleParams.dataBits / 8,
    "MEM_BEAT_BYTES" -> p(HostMemChannelKey).axi4BundleParams.dataBits / 8,
    // These are fixed by the AXI4 standard, only used in SW DRAM model
    "MEM_SIZE_BITS"  -> AXI4Parameters.sizeBits,
    "MEM_LEN_BITS"   -> AXI4Parameters.lenBits,
    "MEM_RESP_BITS"  -> AXI4Parameters.respBits,
    // Address width of the aggregated host-DRAM space
    "CPU_MANAGED_AXI4_ID_BITS"    -> cpu_managed_axi4.map(_.params.idBits)      .getOrElse(0).toLong,
    "CPU_MANAGED_AXI4_ADDR_BITS"  -> cpu_managed_axi4.map(_.params.addrBits)    .getOrElse(0).toLong,
    "CPU_MANAGED_AXI4_DATA_BITS"  -> cpu_managed_axi4.map(_.params.dataBits)    .getOrElse(0).toLong,
    "CPU_MANAGED_AXI4_STRB_BITS"  -> cpu_managed_axi4.map(_.params.dataBits / 8).getOrElse(0).toLong,
    "CPU_MANAGED_AXI4_BEAT_BYTES" -> cpu_managed_axi4.map(_.params.dataBits / 8).getOrElse(0).toLong,
    // Widths of the AXI4 FPGA to CPU channel
    "FPGA_MANAGED_AXI4_ID_BITS"    -> fpga_managed_axi4.map(_.params.idBits)  .getOrElse(0).toLong,
    "FPGA_MANAGED_AXI4_ADDR_BITS"  -> fpga_managed_axi4.map(_.params.addrBits).getOrElse(0).toLong,
    "FPGA_MANAGED_AXI4_DATA_BITS"  -> fpga_managed_axi4.map(_.params.dataBits).getOrElse(0).toLong,
  ) ++:
   cpu_managed_axi4.map { _ => "CPU_MANAGED_AXI4_PRESENT" -> 1.toLong } ++:
   fpga_managed_axi4.map { _ => "FPGA_MANAGED_AXI4_PRESENT" -> 1.toLong } ++:
   Seq.tabulate[(String, Long)](p(HostMemNumChannels))(idx => s"MEM_HAS_CHANNEL${idx}" -> 1)
  def genHeader(sb: StringBuilder)(implicit p: Parameters) = {
    sb.append("// HeArt\n")
    sb.append(hashSb)
    outer.genHeader(sb)
  }
}
