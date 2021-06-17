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
  val bridgeModuleMap: Map[BridgeIOAnnotation, BridgeModule[_ <: TokenizedRecord]] = bridgeAnnos.map(anno => anno -> addWidget(anno.elaborateWidget)).toMap

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
  val memAXI4Node = AXI4SlaveNode(Seq.tabulate(p(HostMemNumChannels)) { channel =>
    val device = new MemoryDevice
    val base = channel * memChannelParams.size
    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = Seq(AddressSet(base, memChannelParams.size - 1)),
        resources     = device.reg,
        regionType    = RegionType.UNCACHED, // cacheable
        executable    = false,
        supportsWrite = TransferSizes(1, memChannelParams.maxXferBytes),
        supportsRead  = TransferSizes(1, memChannelParams.maxXferBytes),
        interleavedId = Some(0))), // slave does not interleave read responses
      beatBytes = memChannelParams.beatBytes)
  })

  // In keeping with the Nasti implementation, we put all channels on a single XBar.
  val xbar = AXI4Xbar()
  p(HostMemIdSpaceKey) match {
    case Some(AXI4IdSpaceConstraint(idBits, maxFlight)) =>
      (memAXI4Node :*= AXI4Buffer()
                   :*= AXI4UserYanker(Some(maxFlight))
                   :*= AXI4IdIndexer(idBits)
                   :*= AXI4Buffer()
                   :*= xbar)
    case None =>
      (memAXI4Node :*= AXI4Buffer()
                   :*= xbar)
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
    println("Host-FPGA DRAM Allocation Map:")
    sortedRegionTuples.zip(dramOffsets).foreach({ case ((bridgeSeq, addresses), offset) =>
      val regionName = bridgeSeq.head.memoryRegionName
      val bridgeNames = bridgeSeq.map(_.getWName).mkString(", ")
      println(f"  ${regionName} -> [0x${offset}%X, 0x${offset + BytesOfDRAMRequired(addresses) - 1}%X]")
      println(f"    Associated bridges: ${bridgeNames}")
    })
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
  val dma = IO(Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) }))))

  // Hack: Don't touch the ports so that we can use FPGATop as top-level in ML simulation
  dontTouch(ctrl)
  dontTouch(mem)
  dontTouch(dma)
  (mem zip outer.memAXI4Node.in).foreach { case (io, (bundle, _)) =>
    require(bundle.params.idBits <= p(HostMemChannelKey).idBits,
      s"""| Required memory channel ID bits exceeds that present on host.
          | Required: ${bundle.params.idBits} Available: ${p(HostMemChannelKey).idBits}
          | Enable host ID reuse with the HostMemIdSpaceKey""".stripMargin)
    io <> bundle
  }

  val sim = Module(new SimWrapper(p(SimWrapperKey)))
  val simIo = sim.channelPorts

  case class DmaInfo(name: String, port: NastiIO, size: BigInt)
  val dmaInfoBuffer = new mutable.ListBuffer[DmaInfo]

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

    bridgeMod.module match {
      case widget: HasDMA => dmaInfoBuffer += DmaInfo(bridgeMod.getWName, widget.dma, widget.dmaSize)
      case _ => Nil
    }
  })

  // Sort the list of DMA ports by address region size, largest to smallest
  val dmaInfoSorted = dmaInfoBuffer.sortBy(_.size).reverse.toSeq
  // Build up the address map using the sorted list,
  // auto-assigning base addresses as we go.
  val dmaAddrMap = dmaInfoSorted.foldLeft((BigInt(0), List.empty[AddrMapEntry])) {
    case ((startAddr, addrMap), DmaInfo(widgetName, _, reqSize)) =>
      // Round up the size to the nearest power of 2
      val regionSize = 1 << log2Ceil(reqSize)
      val region = MemRange(startAddr, regionSize, MemAttr(AddrMapProt.RW))

      (startAddr + regionSize, AddrMapEntry(widgetName, region) :: addrMap)
  }._2.reverse
  val dmaPorts = dmaInfoSorted.map(_.port)

  if (dmaPorts.isEmpty) {
    val dmaParams = p.alterPartial({ case NastiKey => p(DMANastiKey) })
    val error = Module(new NastiErrorSlave()(dmaParams))
    error.io <> dma
  } else if (dmaPorts.size == 1) {
    dmaPorts(0) <> dma
  } else {
    val dmaParams = p.alterPartial({ case NastiKey => p(DMANastiKey) })
    val router = Module(new NastiRecursiveInterconnect(
      1, new AddrMap(dmaAddrMap))(dmaParams))
    router.io.masters.head <> NastiQueue(dma)(dmaParams)
    dmaPorts.zip(router.io.slaves).foreach { case (dma, slave) => dma <> NastiQueue(slave)(dmaParams) }
  }

  outer.genCtrlIO(ctrl)
  outer.printHostDRAMSummary

  val addrConsts = dmaAddrMap.map {
    case AddrMapEntry(name, MemRange(addr, _, _)) =>
      (s"${name.toUpperCase}_DMA_ADDR" -> addr.longValue)
  }

  val headerConsts = addrConsts ++ List[(String, Long)](
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
    "DMA_ID_BITS"    -> dma.nastiXIdBits,
    "DMA_ADDR_BITS"  -> dma.nastiXAddrBits,
    "DMA_DATA_BITS"  -> dma.nastiXDataBits,
    "DMA_STRB_BITS"  -> dma.nastiWStrobeBits,
    "DMA_BEAT_BYTES" -> p(DMANastiKey).dataBits / 8,
    "DMA_SIZE"       -> log2Ceil(p(DMANastiKey).dataBits / 8)
  ) ++ Seq.tabulate[(String, Long)](p(HostMemNumChannels))(idx => s"MEM_HAS_CHANNEL${idx}" -> 1)
  def genHeader(sb: StringBuilder)(implicit p: Parameters) = outer.genHeader(sb)
}
