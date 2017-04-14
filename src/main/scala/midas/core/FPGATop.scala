package midas
package core

import junctions._
import widgets._
import chisel3._
import chisel3.util._
import config.{Parameters, Field}
import scala.collection.mutable.ArrayBuffer

case object MemNastiKey extends Field[NastiParameters]
case object FpgaMMIOSize extends Field[BigInt]

class FPGATopIO(implicit p: Parameters) extends _root_.util.ParameterizedBundle()(p) {
  val ctrl = Flipped(new WidgetMMIO()(p alterPartial ({ case NastiKey => p(CtrlNastiKey) })))
  val mem  = new NastiIO()(p alterPartial ({ case NastiKey => p(MemNastiKey) }))
}

// Platform agnostic wrapper of the simulation models for FPGA 
// TODO: Tilelink2 Port
class FPGATop(simIoType: SimWrapperIO)(implicit p: Parameters) extends Module with HasWidgets {
  val io = IO(new FPGATopIO)
  // Simulation Target
  val sim = Module(new SimBox(simIoType))
  val simIo = sim.io.io
  val memIoSize = (Seq(sim.io.io.nasti, sim.io.io.axi4) foldLeft 0)(_ + _.size)
  // This reset is used to return the emulation to time 0.
  val simReset = Wire(Bool())

  implicit val channelWidth = sim.channelWidth
  sim.io.clock := clock
  sim.io.reset := reset || simReset

  val master = addWidget(new EmulationMaster, "Master")
  simReset := master.io.simReset

  val defaultIOWidget = addWidget(new PeekPokeIOWidget(
    simIo.wireInputs map SimUtils.getChunks,
    simIo.wireOutputs map SimUtils.getChunks),
    "DefaultIOWidget")
  defaultIOWidget.io.step <> master.io.step
  master.io.done := defaultIOWidget.io.idle
  defaultIOWidget.reset := reset || simReset

  // Note we are connecting up target reset here; we override part of this
  // assignment below when connecting the memory models to this same reset
  (simIo.wireIns zip defaultIOWidget.io.ins) foreach { case (x, y) => x <> y }
  (defaultIOWidget.io.outs zip simIo.wireOuts) foreach { case (x, y) => x <> y }

  if (p(EnableSnapshot)) {
    val daisyController = addWidget(new DaisyController(simIo.daisy), "DaisyChainController")
    daisyController.reset := reset || simReset
    daisyController.io.daisy <> simIo.daisy

    // TODO: ReadyValidIO Traces
    val traceWidget = addWidget(new IOTraceWidget(
      simIo.wireInputs map SimUtils.getChunks,
      simIo.wireOutputs map SimUtils.getChunks,
      simIo.readyValidInputs,
      simIo.readyValidOutputs),
      "IOTraces")
    traceWidget.reset := reset || simReset
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
        (channel.host.hValid.dir: @unchecked) match {
          case INPUT  =>
            import chisel3.core.ExplicitCompileOptions.NotStrict // to connect nasti & axi4
            channel.target <> target
            channel.host.hValid := port.fromHost.hValid || simResetNext
            ready += channel.host.hReady
          case OUTPUT =>
            import chisel3.core.ExplicitCompileOptions.NotStrict // to connect nasti & axi4
            target <> channel.target
            channel.host.hReady := port.toHost.hReady
            valid += channel.host.hValid
        }
      case (target: Bundle, b: Bundle) =>
        b.elements.toList foreach { case (name, wire) =>
          loop(target.elements(name), wire)
        }
      case (target: Vec[_], v: Vec[_]) =>
        assert(target.size == v.size)
        (target.toSeq zip v.toSeq) foreach loop
    }

    loop(port.hBits -> wires)
    port.toHost.hValid := valid reduce (_ && _)
    port.fromHost.hReady := ready reduce (_ && _)
  }

  // Host Memory Channels
  // Masters = Target memory channels + loadMemWidget
  val arb = Module(new NastiArbiter(memIoSize+1)(p alterPartial ({ case NastiKey => p(MemNastiKey) })))
  io.mem <> arb.io.slave
  if (p(MemModelKey) != None) {
    val loadMem = addWidget(new LoadMemWidget(MemNastiKey), "LOADMEM")
    loadMem.reset := reset || simReset
    arb.io.master(memIoSize) <> loadMem.io.toSlaveMem
  }

  // Instantiate endpoint widgets
  defaultIOWidget.io.tReset.ready := (simIo.endpoints foldLeft Bool(true)){ (resetReady, endpoint) =>
    ((0 until endpoint.size) foldLeft resetReady){ (ready, i) =>
      val widget = endpoint match {
        case _: SimNastiMemIO | _: SimAXI4MemIO =>
          val param = p alterPartial ({ case NastiKey => p(MemNastiKey) })
          val model = (p(MemModelKey): @unchecked) match {
            case Some(modelGen) => addWidget(modelGen(param), s"MemModel_$i")
            case None => addWidget(new NastiWidget()(param), s"NastiWidget_$i")
          }
          arb.io.master(i) <> model.io.host_mem
          model
      }
      widget.reset := reset || simReset
      channels2Port(widget.io.hPort, endpoint(i)._2)
      // each widget should have its own reset queue
      val resetQueue = Module(new Queue(Bool(), 4))
      resetQueue.reset := reset || simReset
      widget.io.tReset <> resetQueue.io.deq
      resetQueue.io.enq.bits := defaultIOWidget.io.tReset.bits
      resetQueue.io.enq.valid := defaultIOWidget.io.tReset.valid
      ready && resetQueue.io.enq.ready
    }
  }

  genCtrlIO(io.ctrl, p(FpgaMMIOSize))

  val headerConsts = List(
    "CTRL_ID_BITS"   -> io.ctrl.nastiExternal.idBits,
    "CTRL_ADDR_BITS" -> io.ctrl.nastiXAddrBits,
    "CTRL_DATA_BITS" -> io.ctrl.nastiXDataBits,
    "CTRL_STRB_BITS" -> io.ctrl.nastiWStrobeBits,
    "MEM_ADDR_BITS"  -> arb.nastiXAddrBits,
    "MEM_DATA_BITS"  -> arb.nastiXDataBits,
    "MEM_ID_BITS"    -> arb.nastiXIdBits,
    "MEM_SIZE_BITS"  -> arb.nastiXSizeBits,
    "MEM_LEN_BITS"   -> arb.nastiXLenBits,
    "MEM_RESP_BITS"  -> arb.nastiXRespBits,
    "MEM_STRB_BITS"  -> arb.nastiWStrobeBits
  )
}
