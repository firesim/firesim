package midas
package core

import util.ParameterizedBundle // from rocketchip
import widgets._
import chisel3._
import chisel3.util._
import cde.{Parameters, Field}
import junctions._

case object SlaveNastiKey extends Field[NastiParameters]
case object ZynqMMIOSize extends Field[BigInt]

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new WidgetMMIO()(p alter Map(NastiKey -> p(CtrlNastiKey))))
  val slave  = new NastiIO()(p alter Map(NastiKey -> p(SlaveNastiKey)))
}

class ZynqShim(_simIo: SimWrapperIO, memIo: SimMemIO)(implicit p: Parameters) extends Module with HasWidgets {
  val io = IO(new ZynqShimIO)
  // Simulation Target
  val sim = Module(new SimBox(_simIo))
  val simIo = sim.io.io
  // This reset is used to return the emulation to time 0.
  val simReset = Wire(Bool())

  implicit val channelWidth = sim.channelWidth
  sim.io.clock := clock
  sim.io.reset := reset || simReset

  // Exclude only NastiIO for now
  val pokedIns = simIo.inputs filterNot (x => memIo(x._1))
  val peekedOuts = simIo.outputs filterNot (x => memIo(x._1))
  
  val master = addWidget(new EmulationMaster, "EmulationMaster")
  simReset := master.io.simReset

  def getChunks(args: (Bits, String)) = args match {
    case (wire, name) => name -> SimUtils.getChunks(wire)
  }

  val defaultIOWidget = addWidget(
    new PeekPokeIOWidget(pokedIns map getChunks, peekedOuts map getChunks),
    "DefaultIOWidget")
  defaultIOWidget.io.step <> master.io.step
  master.io.done := defaultIOWidget.io.idle
  defaultIOWidget.reset := reset || simReset

  // Get only the channels driven by the PeekPoke widget and exclude reset
  val inputChannels = pokedIns flatMap { case (wire, name) => simIo.getIns(wire) }
  val outputChannels = peekedOuts flatMap { case (wire, name) => simIo.getOuts(wire) }

  def connectChannels(sinks: Seq[DecoupledIO[UInt]], srcs: Seq[DecoupledIO[UInt]]): Unit =
    sinks.zip(srcs) foreach { case (src, sink) =>  sink <> src }

  // Note we are connecting up target reset here; we override part of this
  // assignment below when connecting the memory models to this same reset
  connectChannels(inputChannels, defaultIOWidget.io.ins)
  connectChannels(defaultIOWidget.io.outs, outputChannels)

  // Host Memory Channels
  // Masters = Target memory channels + loadMemWidget
  val arb = Module(new NastiArbiter(memIo.size+1)(p alter Map(NastiKey -> p(SlaveNastiKey))))
  val loadMem = addWidget(new LoadMemWidget(SlaveNastiKey), "LOADMEM")
  arb.io.master(memIo.size) <> loadMem.io.toSlaveMem
  io.slave <> arb.io.slave

  if (p(EnableSnapshot)) {
    val daisyController = addWidget(new DaisyController(simIo.daisy), "DaisyChainController")
    daisyController.io.daisy <> simIo.daisy

    val traceWidget = addWidget(
      new IOTraceWidget(simIo.inputs map getChunks, simIo.outputs map getChunks),
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
    def andReduceChunks(b: Bits): Bool = {
      b.dir match {
        case OUTPUT =>
          val chunks = simIo.getOuts(b)
          chunks.foldLeft(Bool(true))(_ && _.valid)
        case INPUT =>
          val chunks = simIo.getIns(b)
          chunks.foldLeft(Bool(true))(_ && _.ready)
        case _ => throw new RuntimeException("Wire must have a direction")
      }
    }
    // First reduce the chunks for each field; and then the fields themselves
    port.toHost.hValid := outWires map (andReduceChunks(_)) reduce(_ && _)
    port.fromHost.hReady := inWires map (andReduceChunks(_)) reduce(_ && _)
    // Pass the hReady back to the chunks of all target driven fields
    outWires foreach {(outWire: Bits) => {
      val chunks = simIo.getOuts(outWire)
      chunks foreach (_.ready := port.toHost.hReady)
    }}
    // Pass the hValid back to the chunks for all target sunk fields
    inWires foreach {(inWire: Bits) => {
      val chunks = simIo.getIns(inWire)
      chunks foreach (_.valid := port.fromHost.hValid || simResetNext)
    }}
  }

  private def channels2Port[T <: Data](port: HostPortIO[T], wires: T): Unit = {
    hostConnect(port, wires)
    targetConnect(port.hBits -> wires)
  }

  defaultIOWidget.io.tReset.ready := ((0 until memIo.size) foldLeft Bool(true)){(ready, i) =>
    val model = addWidget(
      (p(MemModelKey): @unchecked) match {
        case Some(modelGen) => modelGen(p alter Map(NastiKey -> p(SlaveNastiKey)))
        case None => new SimpleLatencyPipe()(p alter Map(NastiKey -> p(SlaveNastiKey)))
      }, s"MemModel_$i")

    arb.io.master(i) <> model.io.host_mem
    model.reset := reset || simReset
    model.io.tReset.bits := defaultIOWidget.io.tReset.bits
    model.io.tReset.valid := defaultIOWidget.io.tReset.valid
    channels2Port(model.io.tNasti, memIo(i))
    ready && model.io.tReset.ready
  }

  genCtrlIO(io.master, p(ZynqMMIOSize))
}
