// See LICENSE for license details.

package midas
package core

import junctions._
import widgets._
import chisel3._
import chisel3.util._
import chisel3.core.ActualDirection
import chisel3.core.DataMirror.directionOf
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util.{DecoupledHelper}

import scala.collection.mutable

case object DMANastiKey extends Field[NastiParameters]
case object FpgaMMIOSize extends Field[BigInt]

// The AXI4 widths for a single host-DRAM channel
case object HostMemChannelNastiKey extends Field[NastiParameters]
// The number of host-DRAM channels -> all channels must have the same AXI4 widths
case object HostMemNumChannels extends Field[Int]
// The aggregate memory-space seen by masters wanting DRAM
case object MemNastiKey extends Field[NastiParameters]

class FPGATopIO(implicit val p: Parameters) extends WidgetIO {
  val dma  = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
  val mem  = Vec(4, new NastiIO()(p alterPartial ({ case NastiKey => p(HostMemChannelNastiKey) })))
}

// Platform agnostic wrapper of the simulation models for FPGA
class FPGATop(simIoType: SimWrapperChannels)(implicit p: Parameters) extends Module with HasWidgets {
  val io = IO(new FPGATopIO)
  // Simulation Target
  val sim = Module(new SimBox(simIoType.cloneType))
  val simIo = sim.io.channelPorts
  // This reset is used to return the simulation to time 0.
  val master = addWidget(new SimulationMaster)
  val simReset = master.io.simReset

  sim.io.clock     := clock
  sim.io.reset     := reset.toBool || simReset
  sim.io.hostReset := simReset

  private def channels2Port(endpointPort: HostPortIO[Data], endpointAnno: EndpointIOAnnotation): Unit = {
    // Aggregate input tokens into a single larger token
    val local2globalName = endpointAnno.channelMapping.toMap
    val toHostChannels, fromHostChannels = mutable.ArrayBuffer[ReadyValidIO[Data]]()

    // Bind payloads to HostPort, and collect channels
    for ((field, localName) <- endpointPort.inputWireChannels) {
      val tokenChannel = simIo.wireOutputPortMap(local2globalName(localName))
      field := tokenChannel.bits
      toHostChannels += tokenChannel
    }

    for ((field, localName) <- endpointPort.outputWireChannels) {
      val tokenChannel = simIo.wireInputPortMap(local2globalName(localName))
      tokenChannel.bits := field
      fromHostChannels += tokenChannel
    }

    for ((field, localName) <- endpointPort.inputRVChannels) {
      val (fwdChPort, revChPort) = simIo.rvOutputPortMap(local2globalName(localName))
      field.bits  := fwdChPort.bits.bits
      field.valid := fwdChPort.bits.valid
      revChPort.bits := field.ready
      fromHostChannels += revChPort
      toHostChannels += fwdChPort
    }

    for ((field, localName) <- endpointPort.outputRVChannels) {
      val (fwdChPort, revChPort) = simIo.rvInputPortMap(local2globalName(localName))
      fwdChPort.bits.bits := field.bits
      fwdChPort.bits.valid := field.valid
      field.ready := revChPort.bits
      fromHostChannels += fwdChPort
      toHostChannels += revChPort
    }

    endpointPort.toHost.hValid := toHostChannels.foldLeft(true.B)(_ && _.valid)
    endpointPort.fromHost.hReady := fromHostChannels.foldLeft(true.B)(_ && _.ready)

    // Dequeue from toHost channels only if all toHost tokens are available,
    // and the endpoint consumes it
    val toHostHelper   = DecoupledHelper((endpointPort.toHost.hReady +: toHostChannels.map(_.valid)):_*)
    toHostChannels.foreach(ch => ch.ready := toHostHelper.fire(ch.valid))

    // Enqueue into the toHost channels only once all toHost channels can accept the token
    val fromHostHelper = DecoupledHelper((endpointPort.fromHost.hValid +: fromHostChannels.map(_.ready)):_*)
    fromHostChannels.foreach(ch => ch.valid := fromHostHelper.fire(ch.ready))
  }

  val memPorts = new mutable.ListBuffer[NastiIO]
  case class DmaInfo(name: String, port: NastiIO, size: BigInt)
  val dmaInfoBuffer = new mutable.ListBuffer[DmaInfo]

  // Instantiate endpoint widgets.
  simIo.endpointAnnos.map({ endpointAnno =>
    val widgetChannelPrefix = s"${endpointAnno.target.ref}"
    val widget: EndpointWidget = addWidget(endpointAnno.widget(p))
    widget.reset := reset.toBool || simReset
    widget match {
      case model: midas.models.FASEDMemoryTimingModel =>
        memPorts += model.io.host_mem
        model.hPort.hBits.axi4.aw.bits.user := DontCare
        model.hPort.hBits.axi4.aw.bits.region := DontCare
        model.hPort.hBits.axi4.ar.bits.user := DontCare
        model.hPort.hBits.axi4.ar.bits.region := DontCare
        model.hPort.hBits.axi4.w.bits.id := DontCare
        model.hPort.hBits.axi4.w.bits.user := DontCare
      case peekPoke: PeekPokeIOWidget =>
        peekPoke.io.step <> master.io.step
        master.io.done := peekPoke.io.idle
      case _ =>
    }
    widget.hPort match {
      // For this form of HostPort, the endpoint binds 1:1 to each channel
      // presented by the simulation wrapper.
      case hPort: ChannelizedHostPortIO => {
        hPort.inputChannelNames.foreach(chName =>
          hPort.elements(chName) <> simIo.elements(s"${widgetChannelPrefix}_${chName}_source")
        )
        hPort.outputChannelNames.foreach(chName =>
          simIo.elements(s"${widgetChannelPrefix}_${chName}_sink") <> hPort.elements(chName)
        )
      }
      // For a HostPortIO channels are aggregated into a single input and output token
      case hPort: HostPortIO[Data] => channels2Port(hPort, endpointAnno)
    }

    widget match {
      case widget: HasDMA => dmaInfoBuffer += DmaInfo(widget.getWName, widget.dma, widget.dmaSize)
      case _ => Nil
    }
  })

  // Host Memory Channels
  // Masters = Target memory channels + loadMemWidget
  val numMemModels = memPorts.length
  val nastiP = p.alterPartial({ case NastiKey => p(MemNastiKey) })
  val loadMem = addWidget(new LoadMemWidget(MemNastiKey))
  loadMem.reset := reset.toBool || simReset
  memPorts += loadMem.io.toSlaveMem

  val channelSize = BigInt(1) << p(HostMemChannelNastiKey).addrBits
  val hostMemAddrMap = new AddrMap(Seq.tabulate(p(HostMemNumChannels))(i =>
    AddrMapEntry(s"memChannel$i", MemRange(i * channelSize, channelSize, MemAttr(AddrMapProt.RW)))))

  val mem_xbar = Module(new NastiRecursiveInterconnect(numMemModels + 1, hostMemAddrMap)(nastiP))

  io.mem.zip(mem_xbar.io.slaves).foreach({ case (mem, slave) => mem <> NastiQueue(slave)(nastiP) })
  memPorts.zip(mem_xbar.io.masters).foreach({ case (mem_model, master) => master <> mem_model })


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
    error.io <> io.dma
  } else if (dmaPorts.size == 1) {
    dmaPorts(0) <> io.dma
  } else {
    val dmaParams = p.alterPartial({ case NastiKey => p(DMANastiKey) })
    val router = Module(new NastiRecursiveInterconnect(
      1, new AddrMap(dmaAddrMap))(dmaParams))
    router.io.masters.head <> NastiQueue(io.dma)(dmaParams)
    dmaPorts.zip(router.io.slaves).foreach { case (dma, slave) => dma <> NastiQueue(slave)(dmaParams) }
  }

  genCtrlIO(io.ctrl, p(FpgaMMIOSize))

  val addrConsts = dmaAddrMap.map {
    case AddrMapEntry(name, MemRange(addr, _, _)) =>
      (s"${name.toUpperCase}_DMA_ADDR" -> addr.longValue)
  }

  val headerConsts = addrConsts ++ List[(String, Long)](
    "CTRL_ID_BITS"   -> io.ctrl.nastiXIdBits,
    "CTRL_ADDR_BITS" -> io.ctrl.nastiXAddrBits,
    "CTRL_DATA_BITS" -> io.ctrl.nastiXDataBits,
    "CTRL_STRB_BITS" -> io.ctrl.nastiWStrobeBits,
    // These specify channel widths; used mostly in the test harnesses
    "MEM_ADDR_BITS"  -> io.mem(0).nastiXAddrBits,
    "MEM_DATA_BITS"  -> io.mem(0).nastiXDataBits,
    "MEM_ID_BITS"    -> io.mem(0).nastiXIdBits,
    // These are fixed by the AXI4 standard, only used in SW DRAM model
    "MEM_SIZE_BITS"  -> io.mem(0).nastiXSizeBits,
    "MEM_LEN_BITS"   -> io.mem(0).nastiXLenBits,
    "MEM_RESP_BITS"  -> io.mem(0).nastiXRespBits,
    "MEM_STRB_BITS"  -> io.mem(0).nastiWStrobeBits,
    // Address width of the aggregated host-DRAM space
    "DMA_ID_BITS"    -> io.dma.nastiXIdBits,
    "DMA_ADDR_BITS"  -> io.dma.nastiXAddrBits,
    "DMA_DATA_BITS"  -> io.dma.nastiXDataBits,
    "DMA_STRB_BITS"  -> io.dma.nastiWStrobeBits,
    "DMA_WIDTH"      -> p(DMANastiKey).dataBits / 8,
    "DMA_SIZE"       -> log2Ceil(p(DMANastiKey).dataBits / 8)
  )
}
