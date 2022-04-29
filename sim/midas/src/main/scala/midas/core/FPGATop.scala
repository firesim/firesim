// See LICENSE for license details.

package midas
package core

import junctions._
import midas.widgets._
import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.{DecoupledHelper, HeterogeneousBag}

import scala.collection.immutable.ListMap
import scala.collection.mutable

/**
  * The following case objects define the widths of the three AXI4 bus types presented
  * to a simulator.
  */

// The AXI4 key for the DMA bus
case object DMANastiKey extends Field[NastiParameters]
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

class FPGATopIO(implicit val p: Parameters) extends WidgetIO {
  val dma  = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
}

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


// Platform agnostic wrapper of the simulation models for FPGA
class FPGATop(implicit p: Parameters) extends LazyModule with UnpackedWrapperConfig with HasWidgets {
  require(p(HostMemNumChannels) <= 4, "Midas-level simulation harnesses support up to 4 channels")
  require(p(CtrlNastiKey).dataBits == 32,
    "Simulation control bus must be 32-bits wide per AXI4-lite specification")
  lazy val config = p(SimWrapperKey)
  val master = addWidget(new SimulationMaster)
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

  val pcisAXI4BundleParams = AXI4BundleParameters(
    addrBits = p(DMANastiKey).addrBits,
    dataBits = p(DMANastiKey).dataBits,
    idBits   = p(DMANastiKey).idBits)  // Dubious...

  val pcisNode = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
          name       = "cpu-mastered-axi4",
          id         = IdRange(0, 1 << p(DMANastiKey).idBits),
          aligned    = false,
          maxFlight  = None, // None = infinite, else is a per-ID cap
        ))
      )
    )
  )

  val streamingEngine = addWidget(p(StreamEngineInstantiatorKey)(
    StreamEngineParameters(toCPUStreamParams.toSeq, fromCPUStreamParams.toSeq), p)
  )

  streamingEngine.pcisNodeOpt.foreach {
    _ := AXI4Buffer() := pcisNode
  }

  override def genHeader(sb: StringBuilder) {
    super.genHeader(sb)
    targetMemoryRegions.foreach(_.serializeToHeader(sb))
  }

  lazy val module = new FPGATopImp(this)
}

class FPGATopImp(outer: FPGATop)(implicit p: Parameters) extends LazyModuleImp(outer) {

  val master  = outer.master

  val ctrl = IO(Flipped(WidgetMMIO()))
  val mem = IO(Vec(p(HostMemNumChannels), AXI4Bundle(p(HostMemChannelKey).axi4BundleParams)))
  val dma  = IO(Flipped(AXI4Bundle(outer.pcisAXI4BundleParams)))
  // Hack: Don't touch the ports so that we can use FPGATop as top-level in ML simulation
  dontTouch(ctrl)
  dontTouch(mem)
  dontTouch(dma)
  (mem zip outer.memAXI4Nodes.map(_.in.head)).foreach { case (io, (bundle, _)) =>
    require(bundle.params.idBits <= p(HostMemChannelKey).idBits,
      s"""| Required memory channel ID bits exceeds that present on host.
          | Required: ${bundle.params.idBits} Available: ${p(HostMemChannelKey).idBits}
          | Enable host ID reuse with the HostMemIdSpaceKey""".stripMargin)
    io <> bundle
  }

  outer.pcisNode.out.head._1 <> dma

  val sim = Module(new SimWrapper(p(SimWrapperKey)))
  val simIo = sim.channelPorts

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
    "DMA_ID_BITS"    -> dma.params.idBits,
    "DMA_ADDR_BITS"  -> dma.params.addrBits,
    "DMA_DATA_BITS"  -> dma.params.dataBits,
    "DMA_STRB_BITS"  -> dma.params.dataBits / 8,
    "DMA_BEAT_BYTES" -> p(DMANastiKey).dataBits / 8,
    "DMA_SIZE"       -> log2Ceil(p(DMANastiKey).dataBits / 8),
  ) ++ Seq.tabulate[(String, Long)](p(HostMemNumChannels))(idx => s"MEM_HAS_CHANNEL${idx}" -> 1)
  def genHeader(sb: StringBuilder)(implicit p: Parameters) = outer.genHeader(sb)
}
