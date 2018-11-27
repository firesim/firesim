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
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

case object MemNastiKey extends Field[NastiParameters]
case object DMANastiKey extends Field[NastiParameters]
case object FpgaMMIOSize extends Field[BigInt]

class FPGATopIO(implicit p: Parameters) extends WidgetIO {
  val dma  = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
  val mem = new NastiIO()(p alterPartial ({ case NastiKey => p(MemNastiKey) }))
}

// Platform agnostic wrapper of the simulation models for FPGA 
// TODO: Tilelink2 Port
class FPGATop(simIoType: SimWrapperIO)(implicit p: Parameters) extends Module with HasWidgets {
  val io = IO(new FPGATopIO)
  // Simulation Target
  val sim = Module(new SimBox(simIoType.cloneType))
  val simIo = sim.io.channelPorts
  val memIoSize = (simIo.endpoints collect { case x: SimMemIO => x } foldLeft 0)(_ + _.size)
  // This reset is used to return the emulation to time 0.
  val master = addWidget(new EmulationMaster, "Master")
  val simReset = master.io.simReset

  sim.io.clock     := clock
  sim.io.reset     := reset.toBool || simReset
  sim.io.hostReset := simReset

  val defaultIOWidget = addWidget(new PeekPokeIOWidget(
    simIo.pokedInputs,
    simIo.peekedOutputs,
    simIo.pokedReadyValidInputs,
    simIo.peekedReadyValidOutputs),
    "DefaultIOWidget")
  defaultIOWidget.io.step <> master.io.step
  master.io.done := defaultIOWidget.io.idle
  defaultIOWidget.reset := reset.toBool || simReset

  // Note we are connecting up target reset here; we override part of this
  // assignment below when connecting the memory models to this same reset
  simIo.pokedInputs.foreach({case (wire, name) => simIo.elements(name) <> defaultIOWidget.io.ins.elements(name) })
  simIo.peekedOutputs.foreach({case (wire, name) => defaultIOWidget.io.outs.elements(name) <> simIo.elements(name)})
  simIo.pokedReadyValidInputs.foreach({case (wire, name) => simIo.elements(name) <> defaultIOWidget.io.rvins.elements(name) })
  simIo.peekedReadyValidOutputs.foreach({case (wire, name) => defaultIOWidget.io.rvouts.elements(name) <> simIo.elements(name)})

  //if (p(EnableSnapshot)) {
  //  val daisyController = addWidget(new strober.widgets.DaisyController(simIo.daisy), "DaisyChainController")
  //  daisyController.reset := reset.toBool || simReset
  //  daisyController.io.daisy <> simIo.daisy
  // KElvin was here
  //  val traceWidget = addWidget(new strober.widgets.IOTraceWidget(
  //    simIo.wireInputs map SimUtils.getChunks,
  //    simIo.wireOutputs map SimUtils.getChunks,
  //    simIo.readyValidInputs,
  //    simIo.readyValidOutputs),
  //    "IOTraces")
  //  traceWidget.reset := reset.toBool || simReset
  //  traceWidget.io.wireIns <> simIo.wireInTraces
  //  traceWidget.io.wireOuts <> simIo.wireOutTraces
  //  traceWidget.io.readyValidIns <> simIo.readyValidInTraces
  //  traceWidget.io.readyValidOuts <> simIo.readyValidOutTraces
  //  simIo.traceLen := traceWidget.io.traceLen
  //}

  val simResetNext = RegNext(simReset)
  private def channels2Port[T <: Data](port: HostPortIO[T], wires: T): Unit = {
    val valid = ArrayBuffer[Bool]()
    val ready = ArrayBuffer[Bool]()
    def loop[T <: Data](arg: (T, T)): Unit = arg match {
      case (target: ReadyValidIO[_], rv: ReadyValidIO[_]) =>
        val channel = simIo(rv)
        directionOf(channel.fwd.hValid) match {
          case ActualDirection.Input =>
            import chisel3.core.ExplicitCompileOptions.NotStrict // to connect nasti & axi4
            channel.target <> target
            channel.fwd.hValid := port.fromHost.hValid || simResetNext
            channel.rev.hReady := port.toHost.hReady || simResetNext
            ready += channel.fwd.hReady
            valid += channel.rev.hValid
          case ActualDirection.Output =>
            import chisel3.core.ExplicitCompileOptions.NotStrict // to connect nasti & axi4
            target <> channel.target
            channel.fwd.hReady := port.toHost.hReady
            channel.rev.hValid := port.fromHost.hValid
            ready += channel.rev.hReady
            valid += channel.fwd.hValid
          case _ => throw new RuntimeException(s"Unexpected valid direction: ${directionOf(channel.fwd.hValid)}")
        }
      case (target: Record, b: Record) =>
        b.elements.toList foreach { case (name, wire) =>
          loop(target.elements(name), wire)
        }
      case (target: Vec[_], v: Vec[_]) =>
        require(target.size == v.size)
        (target.zip(v)).foreach(loop)
      case (target: Bits, wire: Bits) => directionOf(wire) match {
        case ActualDirection.Input =>
          val channel = simIo(wire)
          channel.bits  := target
          channel.valid := port.fromHost.hValid || simResetNext
          ready += channel.ready
        case ActualDirection.Output =>
          val channel = simIo(wire)
          target := channel.bits
          channel.ready := port.toHost.hReady
          valid += channel.valid
        case _ => throw new RuntimeException(s"Unexpected Bits direction: ${directionOf(wire)}")
      }
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

  val dmaPorts = new ListBuffer[NastiIO]
  val addresses = new ListBuffer[AddressSet]
  val tResetChannel = defaultIOWidget.io.ins.elements("reset")

  // Instantiate endpoint widgets. Keep a tuple of each endpoint's reset channel enq.valid and enq.ready
  //                      Valid, Ready
  val resetEnqTuples: Seq[(Bool, Bool)] = (simIo.endpoints flatMap { endpoint =>
    Seq.tabulate(endpoint.size)({ i =>
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

      if (widget.io.dma.nonEmpty) {
        dmaPorts += widget.io.dma.get
        addresses += widget.io.address.get
      }

      // each widget should have its own reset queue
      val resetQueue = Module(new WireChannel(Bool(), endpoint.clockRatio))
      resetQueue.suggestName(s"resetQueue_${widgetName}")
      resetQueue.io.traceLen := DontCare
      resetQueue.io.trace.ready := DontCare
      resetQueue.reset := reset.toBool || simReset
      widget.io.tReset <> resetQueue.io.out
      resetQueue.io.in.bits := tResetChannel.bits
      resetQueue.io.in.valid := tResetChannel.valid
      (resetQueue.io.in.valid, resetQueue.io.in.ready)
    })
  // HACK: Need to add the tranformed-RTL channel as well
  }) ++ Seq((simIo.wirePortMap("reset").valid, simIo.wirePortMap("reset").ready))

  // Note: This is not LI-BDN compliant... Should implement a forking decoupled helper
  val tResetHelper = DecoupledHelper((resetEnqTuples.map(_._2) ++ Seq(tResetChannel.valid)):_*)
  tResetChannel.ready := tResetHelper.fire(tResetChannel.valid)
  resetEnqTuples.foreach({ case (enqValid, enqReady) => enqValid := tResetHelper.fire(enqReady) })


  if (dmaPorts.isEmpty) {
    val dmaParams = p.alterPartial({ case NastiKey => p(DMANastiKey) })
    val error = Module(new NastiErrorSlave()(dmaParams))
    error.io <> io.dma
  } else if (dmaPorts.size == 1) {
    dmaPorts(0) <> io.dma
  } else {
    val dmaParams = p.alterPartial({ case NastiKey => p(DMANastiKey) })
    val routeFunc = (addr: UInt) => Cat(addresses.map(_.contains(addr)).reverse)
    val router = Module(new NastiRouter(dmaPorts.size, routeFunc)(dmaParams))
    router.io.master <> NastiQueue(io.dma)(dmaParams)
    dmaPorts.zip(router.io.slave).foreach { case (dma, slave) => dma <> slave }
  }

  genCtrlIO(io.ctrl, p(FpgaMMIOSize))

  val headerConsts = List(
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
