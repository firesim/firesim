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
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

case object MemNastiKey extends Field[NastiParameters]
case object DMANastiKey extends Field[NastiParameters]
case object FpgaMMIOSize extends Field[BigInt]

class FPGATopIO(implicit val p: Parameters) extends WidgetIO {
  val dma  = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
  val mem = new NastiIO()(p alterPartial ({ case NastiKey => p(MemNastiKey) }))
}

// Platform agnostic wrapper of the simulation models for FPGA 
// TODO: Tilelink2 Port
class FPGATop(simIoType: SimWrapperIO)(implicit p: Parameters) extends Module with HasWidgets {
  val io = IO(new FPGATopIO)
  // Simulation Target
  val sim = Module(new SimBox(simIoType))
  val simIo = sim.io.io
  val memIoSize = (simIo.endpoints collect { case x: SimMemIO => x } foldLeft 0)(_ + _.size)
  // This reset is used to return the emulation to time 0.
  val simReset = Wire(Bool())

  implicit val channelWidth = sim.channelWidth
  sim.io.clock := clock
  sim.io.reset := reset.toBool || simReset

  val master = addWidget(new EmulationMaster, "Master")
  simReset := master.io.simReset

  val defaultIOWidget = addWidget(new PeekPokeIOWidget(
    simIo.pokedInputs map SimUtils.getChunks,
    simIo.peekedOutputs map SimUtils.getChunks),
    "DefaultIOWidget")
  defaultIOWidget.io.step <> master.io.step
  master.io.done := defaultIOWidget.io.idle
  defaultIOWidget.reset := reset.toBool || simReset

  // Note we are connecting up target reset here; we override part of this
  // assignment below when connecting the memory models to this same reset
  val inputChannels = simIo.pokedInputs flatMap { case (wire, name) => simIo.getIns(wire) }
  val outputChannels = simIo.peekedOutputs flatMap { case (wire, name) => simIo.getOuts(wire) }
  (inputChannels zip defaultIOWidget.io.ins) foreach { case (x, y) => x <> y }
  (defaultIOWidget.io.outs zip outputChannels) foreach { case (x, y) => x <> y }

  if (p(EnableSnapshot)) {
    val daisyController = addWidget(new strober.widgets.DaisyController(simIo.daisy), "DaisyChainController")
    daisyController.reset := reset.toBool || simReset
    daisyController.io.daisy <> simIo.daisy

    val traceWidget = addWidget(new strober.widgets.IOTraceWidget(
      simIo.wireInputs map SimUtils.getChunks,
      simIo.wireOutputs map SimUtils.getChunks,
      simIo.readyValidInputs,
      simIo.readyValidOutputs),
      "IOTraces")
    traceWidget.reset := reset.toBool || simReset
    traceWidget.io.wireIns <> simIo.wireInTraces
    traceWidget.io.wireOuts <> simIo.wireOutTraces
    traceWidget.io.readyValidIns <> simIo.readyValidInTraces
    traceWidget.io.readyValidOuts <> simIo.readyValidOutTraces
    simIo.traceLen := traceWidget.io.traceLen
  }

  val simResetNext = RegNext(simReset)
  private def channels2Port[T <: Data](port: HostPortIO[T], wires: T): Unit = {
    val valid = ArrayBuffer[Bool]()
    val ready = ArrayBuffer[Bool]()
    def loop[T <: Data](arg: (T, T)): Unit = arg match {
      case (target: ReadyValidIO[_], rv: ReadyValidIO[_]) =>
        val (name, channel) = simIo.readyValidMap(rv)
        (directionOf(channel.host.hValid): @unchecked) match {
          case ActualDirection.Input =>
            import chisel3.core.ExplicitCompileOptions.NotStrict // to connect nasti & axi4
            channel.target <> target
            channel.host.hValid := port.fromHost.hValid || simResetNext
            ready += channel.host.hReady
          case ActualDirection.Output =>
            import chisel3.core.ExplicitCompileOptions.NotStrict // to connect nasti & axi4
            target <> channel.target
            channel.host.hReady := port.toHost.hReady
            valid += channel.host.hValid
        }
      case (target: Record, b: Record) =>
        b.elements.toList foreach { case (name, wire) =>
          loop(target.elements(name), wire)
        }
      case (target: Vec[_], v: Vec[_]) =>
        assert(target.size == v.size)
        (target.toSeq zip v.toSeq) foreach loop
      case (target: Bits, wire: Bits) => (directionOf(wire): @unchecked) match {
        case ActualDirection.Input =>
          val channels = simIo.getIns(wire)
          channels.zipWithIndex foreach { case (in, i) =>
            in.bits  := target >> (i * simIo.channelWidth).U
            in.valid := port.fromHost.hValid || simResetNext
          }
          ready ++= channels map (_.ready)
        case ActualDirection.Output =>
          val channels = simIo.getOuts(wire)
          target := Cat(channels.reverse map (_.bits))
          channels foreach (_.ready := port.toHost.hReady)
          valid ++= channels map (_.valid)
      }
      case _ => throw new RuntimeException("Uexpected type tuple in channels2Port")
    }

    loop(port.hBits -> wires)
    port.toHost.hValid := valid.foldLeft(true.B)(_ && _)
    port.fromHost.hReady := ready.foldLeft(true.B)(_ && _)
  }

  // Host Memory Channels
  // Masters = Target memory channels + loadMemWidget
  val nastiP = p.alterPartial({ case NastiKey => p(MemNastiKey) })
  val arb = Module(new NastiArbiter(memIoSize+1)(nastiP))
  io.mem <> NastiQueue(arb.io.slave)(nastiP)
  if (p(MemModelKey) != None) {
    val loadMem = addWidget(new LoadMemWidget(MemNastiKey), "LOADMEM")
    loadMem.reset := reset.toBool || simReset
    arb.io.master(memIoSize) <> loadMem.io.toSlaveMem
  }

  val dmaInfoBuffer = new ListBuffer[(String, NastiIO, BigInt)]

  // Instantiate endpoint widgets
  defaultIOWidget.io.tReset.ready := (simIo.endpoints foldLeft true.B) { (resetReady, endpoint) =>
    ((0 until endpoint.size) foldLeft resetReady) { (ready, i) =>
      val widgetName = (endpoint, p(MemModelKey)) match {
        case (_: SimMemIO, Some(_)) => s"MemModel_$i"
        case (_: SimMemIO, None) => s"NastiWidget_$i"
        case _ => s"${endpoint.widgetName}_$i"
      }
      val widget = addWidget(endpoint.widget(p), widgetName)
      widget.reset := reset.toBool || simReset
      widget match {
        case model: MemModel =>
          arb.io.master(i) <> model.io.host_mem
          model.io.tNasti.hBits.aw.bits.user := DontCare
          model.io.tNasti.hBits.aw.bits.region := DontCare
          model.io.tNasti.hBits.ar.bits.user := DontCare
          model.io.tNasti.hBits.ar.bits.region := DontCare
          model.io.tNasti.hBits.w.bits.id := DontCare
          model.io.tNasti.hBits.w.bits.user := DontCare
        case _ =>
      }
      channels2Port(widget.io.hPort, endpoint(i)._2)

      if (widget.io.dma.nonEmpty)
        dmaInfoBuffer += Tuple3(widgetName, widget.io.dma.get, widget.io.dmaSize)

      // each widget should have its own reset queue
      val resetQueue = Module(new WireChannel(1, endpoint.clockRatio))
      resetQueue.io.traceLen := DontCare
      resetQueue.io.trace.ready := DontCare
      resetQueue.reset := reset.toBool || simReset
      widget.io.tReset <> resetQueue.io.out
      resetQueue.io.in.bits := defaultIOWidget.io.tReset.bits
      resetQueue.io.in.valid := defaultIOWidget.io.tReset.valid
      ready && resetQueue.io.in.ready
    }
  }

  // Sort the list of DMA ports by address region size, largest to smallest
  val dmaInfoSorted = dmaInfoBuffer.sortBy(_._3).reverse.toSeq
  // Build up the address map using the sorted list,
  // auto-assigning base addresses as we go.
  val dmaAddrMap = dmaInfoSorted.foldLeft((BigInt(0), List.empty[AddrMapEntry])) {
    case ((startAddr, addrMap), (widgetName, _, reqSize)) =>
      // Round up the size to the nearest power of 2
      val regionSize = 1 << log2Ceil(reqSize)
      val region = MemRange(startAddr, regionSize, MemAttr(AddrMapProt.RW))

      (startAddr + regionSize, AddrMapEntry(widgetName, region) :: addrMap)
  }._2.reverse
  val dmaPorts = dmaInfoSorted.map(_._2)

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
    dmaPorts.zip(router.io.slaves).foreach { case (dma, slave) => dma <> slave }
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
    "MEM_ADDR_BITS"  -> arb.nastiXAddrBits,
    "MEM_DATA_BITS"  -> arb.nastiXDataBits,
    "MEM_ID_BITS"    -> arb.nastiXIdBits,
    "MEM_SIZE_BITS"  -> arb.nastiXSizeBits,
    "MEM_LEN_BITS"   -> arb.nastiXLenBits,
    "MEM_RESP_BITS"  -> arb.nastiXRespBits,
    "MEM_STRB_BITS"  -> arb.nastiWStrobeBits,
    "DMA_ID_BITS"    -> io.dma.nastiXIdBits,
    "DMA_ADDR_BITS"  -> io.dma.nastiXAddrBits,
    "DMA_DATA_BITS"  -> io.dma.nastiXDataBits,
    "DMA_STRB_BITS"  -> io.dma.nastiWStrobeBits,
    "DMA_WIDTH"      -> p(DMANastiKey).dataBits / 8,
    "DMA_SIZE"       -> log2Ceil(p(DMANastiKey).dataBits / 8)
  )
}
