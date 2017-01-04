package midas
package core

import util.ParameterizedBundle // from rocketchip
import junctions._
import widgets._
import chisel3._
import chisel3.util._
import cde.{Parameters, Field}

case object MemNastiKey extends Field[NastiParameters]
case object FpgaMMIOSize extends Field[BigInt]

class FPGATopIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val ctrl = Flipped(new WidgetMMIO()(p alter Map(NastiKey -> p(CtrlNastiKey))))
  val mem  = new NastiIO()(p alter Map(NastiKey -> p(MemNastiKey)))
}

// Platform agnostic wrapper of the simulation models for FPGA 
// TODO: Tilelink2 Port
class FPGATop(simIoType: SimWrapperIO)(implicit p: Parameters) extends Module with HasWidgets {
  val io = IO(new FPGATopIO)
  // Simulation Target
  val sim = Module(new SimBox(simIoType))
  val simIo = sim.io.io
  val memIo = sim.io.io.mem
  // This reset is used to return the emulation to time 0.
  val simReset = Wire(Bool())

  implicit val channelWidth = sim.channelWidth
  sim.io.clock := clock
  sim.io.reset := reset || simReset

  val master = addWidget(new EmulationMaster, "Master")
  simReset := master.io.simReset

  val defaultIOWidget = addWidget(new PeekPokeIOWidget(
    simIo.pokedIns map SimUtils.getChunks,
    simIo.peekedOuts map SimUtils.getChunks),
    "DefaultIOWidget")
  defaultIOWidget.io.step <> master.io.step
  master.io.done := defaultIOWidget.io.idle
  defaultIOWidget.reset := reset || simReset

  // Get only the channels driven by the PeekPoke widget and exclude reset
  val inputChannels = simIo.pokedIns flatMap { case (wire, name) => simIo.getIns(wire) }
  val outputChannels = simIo.peekedOuts flatMap { case (wire, name) => simIo.getOuts(wire) }

  def connectChannels(sinks: Seq[DecoupledIO[UInt]], srcs: Seq[DecoupledIO[UInt]]): Unit =
    sinks.zip(srcs) foreach { case (src, sink) =>  sink <> src }

  // Note we are connecting up target reset here; we override part of this
  // assignment below when connecting the memory models to this same reset
  connectChannels(inputChannels, defaultIOWidget.io.ins)
  connectChannels(defaultIOWidget.io.outs, outputChannels)

  if (p(EnableSnapshot)) {
    val daisyController = addWidget(new DaisyController(simIo.daisy), "DaisyChainController")
    daisyController.io.daisy <> simIo.daisy

    val traceWidget = addWidget(new IOTraceWidget(
      simIo.inputs map SimUtils.getChunks,
      simIo.outputs map SimUtils.getChunks),
      "IOTraces")
    traceWidget.io.ins <> simIo.inT
    traceWidget.io.outs <> simIo.outT
    simIo.traceLen := traceWidget.io.traceLen
  }

  private def targetConnect[T <: Data](arg: (T, T)): Unit = arg match {
    case (target: Bundle, wires: Bundle) => 
      (target.elements.unzip._2 zip wires.elements.unzip._2) foreach targetConnect
    case (target: Vec[_], wires: Vec[_]) => 
      (target.toSeq zip wires.toSeq) foreach targetConnect
    case (target: Bits, wire: Bits) if wire.dir == OUTPUT =>
      target := Cat(simIo.getOuts(wire).reverse map (_.bits))
    case (target: Bits, wire: Bits) if wire.dir == INPUT => 
      simIo.getIns(wire).zipWithIndex foreach {case (in, i) =>
        in.bits := target >> UInt(i * simIo.channelWidth)
      }
    case _ =>
  }

  val simResetNext = RegNext(simReset)
  private def hostConnect[T <: Data](port: HostPortIO[T], wires: T): Unit = {
    val (ins, outs) = SimUtils.parsePorts(wires)
    val inWires = ins map (_._1)
    val outWires = outs map(_._1)
    def andReduceChunks(b: Bits): Bool = b.dir match {
      case OUTPUT =>
        val chunks = simIo.getOuts(b)
        chunks.foldLeft(Bool(true))(_ && _.valid)
      case INPUT =>
        val chunks = simIo.getIns(b)
        chunks.foldLeft(Bool(true))(_ && _.ready)
      case _ => throw new RuntimeException("Wire must have a direction")
    }
    // First reduce the chunks for each field; and then the fields themselves
    port.toHost.hValid := outWires map andReduceChunks reduce (_ && _)
    port.fromHost.hReady := inWires map andReduceChunks reduce (_ && _)
    // Pass the hReady back to the chunks of all target driven fields
    outWires foreach { outWire: Bits =>
      val chunks = simIo.getOuts(outWire)
      chunks foreach (_.ready := port.toHost.hReady)
    }
    // Pass the hValid back to the chunks for all target sunk fields
    inWires foreach { inWire: Bits =>
      val chunks = simIo.getIns(inWire)
      chunks foreach (_.valid := port.fromHost.hValid || simResetNext)
    }
  }

  private def channels2Port[T <: Data](port: HostPortIO[T], wires: T): Unit = {
    hostConnect(port, wires)
    targetConnect(port.hBits -> wires)
  }

  // Host Memory Channels
  // Masters = Target memory channels + loadMemWidget
  val arb = Module(new NastiArbiter(memIo.size+1)(p alter Map(NastiKey -> p(MemNastiKey))))
  io.mem <> arb.io.slave
  if (p(MemModelKey) != None) {
    val loadMem = addWidget(new LoadMemWidget(MemNastiKey), "LOADMEM")
    arb.io.master(memIo.size) <> loadMem.io.toSlaveMem
  }

  // Instantiate endpoint widgets
  defaultIOWidget.io.tReset.ready := (simIo.endpoints foldLeft Bool(true)){ (resetReady, endpoint) =>
    ((0 until endpoint.size) foldLeft resetReady){ (ready, i) =>
      val widget = endpoint match {
        case _: SimMemIO =>
          val param = p alter Map(NastiKey -> p(MemNastiKey))
          val model = (p(MemModelKey): @unchecked) match {
            case Some(modelGen) => addWidget(modelGen(param), s"MemModel_$i")
            case None => addWidget(new NastiWidget()(param), s"NastiWidget_$i")
          }
          arb.io.master(i) <> model.io.host_mem
          model
      }
      widget.reset := reset || simReset
      channels2Port(widget.io.hPort, endpoint(i))
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
    "MEM_ID_BITS"    -> arb.nastiExternal.idBits,
    "MEM_ADDR_BITS"  -> arb.nastiXAddrBits,
    "MEM_DATA_BITS"  -> arb.nastiXDataBits,
    "MEM_STRB_BITS"  -> arb.nastiWStrobeBits
  )
}
