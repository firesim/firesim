package firesim.endpoints

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.TracedInstruction
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.TileKey

import midas.core.{HostPort}
import midas.widgets._
import testchipip.{StreamIO, StreamChannel}
import icenet.{NICIOvonly, RateLimiterSettings}
import icenet.IceNIC._
import junctions.{NastiIO, NastiKey}
import TokenQueueConsts._

// Hack: In a457f658a, RC added the Clocked trait to TracedInstruction, which breaks midas
// I/O token handling. The non-Clock fields of this Bundle should be factored out in rocket chip.
// For now, we create second Bundle with Clock (of type Clock) and Reset removed
class DeclockedTracedInstruction(private val proto: TracedInstruction) extends Bundle {
  val valid = Bool()
  val iaddr = UInt(proto.iaddr.getWidth.W)
  val insn  = UInt(proto.insn.getWidth.W)
  val priv  = UInt(proto.priv.getWidth.W)
  val exception = Bool()
  val interrupt = Bool()
  val cause = UInt(proto.cause.getWidth.W)
  val tval = UInt(proto.tval.getWidth.W)
}

object DeclockedTracedInstruction {
  // Generates a hardware Vec of declockedInsns
  def fromVec(clockedVec: Vec[TracedInstruction]): Vec[DeclockedTracedInstruction] = {
    val declockedVec = clockedVec.map(insn => Wire(new DeclockedTracedInstruction(insn.cloneType)))
    declockedVec.zip(clockedVec).foreach({ case (declocked, clocked) =>
      declocked.valid := clocked.valid
      declocked.iaddr := clocked.iaddr
      declocked.insn := clocked.insn
      declocked.priv := clocked.priv
      declocked.exception := clocked.exception
      declocked.interrupt := clocked.interrupt
      declocked.cause := clocked.cause
      declocked.tval := clocked.tval
    })
    VecInit(declockedVec)
  }

  // Generates a Chisel type from that returned by a Diplomatic node's in() or .out() methods
  def fromNode(ports: Seq[(Vec[TracedInstruction], Any)]): Seq[Vec[DeclockedTracedInstruction]] = ports.map({
    case (bundle, _) => Vec(bundle.length, new DeclockedTracedInstruction(bundle.head.cloneType))
  })
}

// The IO matched on by the TracerV endpoint: a wrapper around a heterogenous
// bag of vectors. Each entry is Vec of committed instructions
class TraceOutputTop(private val traceProto: Seq[Vec[DeclockedTracedInstruction]]) extends Bundle {
  val traces = Output(HeterogeneousBag(traceProto.map(_.cloneType)))
  def getProto() = traceProto
}

class TracerVEndpoint(traceProto: Seq[Vec[DeclockedTracedInstruction]]) extends BlackBox with IsEndpoint {
  val io = IO(Flipped(new TraceOutputTop(traceProto)))
  val endpointIO = HostPort(io)
  def widget = (p: Parameters) => new TracerVWidget(traceProto)(p)
  generateAnnotations()
}

object TracerVEndpoint {
  def apply(port: TraceOutputTop)(implicit p:Parameters): Seq[TracerVEndpoint] = {
    val ep = Module(new TracerVEndpoint(port.getProto))
    ep.io <> port
    Seq(ep)
  }
}

class TracerVWidget(traceProto: Seq[Vec[DeclockedTracedInstruction]])(implicit p: Parameters) extends EndpointWidget()(p)
    with UnidirectionalDMAToHostCPU {
  val io = IO(new WidgetIO)
  val hPort = IO(HostPort(Flipped(new TraceOutputTop(traceProto))))

  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid
  //trigger conditions
  //TODO: trigger conditions currently assume only 1 instruction is retired every cycle
  //This work for rocket, but will not work for BOOM

  //Program Counter trigger value can be configured externally
  val hostTriggerPCWidthOffset = io.hPort.hBits.traces(0)(0).iaddr.getWidth - p(CtrlNastiKey).dataBits
  val hostTriggerPCLowWidth = if (hostTriggerPCWidthOffset > 0) p(CtrlNastiKey).dataBits else io.hPort.hBits.traces(0)(0).iaddr.getWidth
  val hostTriggerPCHighWidth = if (hostTriggerPCWidthOffset > 0) hostTriggerPCWidthOffset else 0

  val hostTriggerPCStartHigh = RegInit(0.U(hostTriggerPCHighWidth.W))
  val hostTriggerPCStartLow = RegInit(0.U(hostTriggerPCLowWidth.W))
  attach(hostTriggerPCStartHigh, "hostTriggerPCStartHigh", WriteOnly)
  attach(hostTriggerPCStartLow, "hostTriggerPCStartLow", WriteOnly)
  val hostTriggerPCStart = Cat(hostTriggerPCStartHigh, hostTriggerPCStartLow)
  val triggerPCStart = RegInit(0.U((io.hPort.hBits.traces(0)(0).iaddr.getWidth).W))
  triggerPCStart := hostTriggerPCStart

  val hostTriggerPCEndHigh = RegInit(0.U(hostTriggerPCHighWidth.W))
  val hostTriggerPCEndLow = RegInit(0.U(hostTriggerPCLowWidth.W))
  attach(hostTriggerPCEndHigh, "hostTriggerPCEndHigh", WriteOnly)
  attach(hostTriggerPCEndLow, "hostTriggerPCEndLow", WriteOnly)
  val hostTriggerPCEnd = Cat(hostTriggerPCEndHigh, hostTriggerPCEndLow)
  val triggerPCEnd = RegInit(0.U((io.hPort.hBits.traces(0)(0).iaddr.getWidth).W))
  triggerPCEnd := hostTriggerPCEnd

  //Cycle count trigger
  val hostTriggerCycleCountWidthOffset = 64 - p(CtrlNastiKey).dataBits
  val hostTriggerCycleCountLowWidth = if (hostTriggerCycleCountWidthOffset > 0) p(CtrlNastiKey).dataBits else 64
  val hostTriggerCycleCountHighWidth = if (hostTriggerCycleCountWidthOffset > 0) hostTriggerCycleCountWidthOffset else 0

  val hostTriggerCycleCountStartHigh = RegInit(0.U(hostTriggerCycleCountHighWidth.W))
  val hostTriggerCycleCountStartLow = RegInit(0.U(hostTriggerCycleCountLowWidth.W))
  attach(hostTriggerCycleCountStartHigh, "hostTriggerCycleCountStartHigh", WriteOnly)
  attach(hostTriggerCycleCountStartLow, "hostTriggerCycleCountStartLow", WriteOnly)
  val hostTriggerCycleCountStart = Cat(hostTriggerCycleCountStartHigh, hostTriggerCycleCountStartLow)
  val triggerCycleCountStart = RegInit(0.U(64.W))
  triggerCycleCountStart := hostTriggerCycleCountStart

  val hostTriggerCycleCountEndHigh = RegInit(0.U(hostTriggerCycleCountHighWidth.W))
  val hostTriggerCycleCountEndLow = RegInit(0.U(hostTriggerCycleCountLowWidth.W))
  attach(hostTriggerCycleCountEndHigh, "hostTriggerCycleCountEndHigh", WriteOnly)
  attach(hostTriggerCycleCountEndLow, "hostTriggerCycleCountEndLow", WriteOnly)
  val hostTriggerCycleCountEnd = Cat(hostTriggerCycleCountEndHigh, hostTriggerCycleCountEndLow)
  val triggerCycleCountEnd = RegInit(0.U(64.W))
  triggerCycleCountEnd := hostTriggerCycleCountEnd

  val trace_cycle_counter = RegInit(0.U(64.W))
  when (tFire) {
    trace_cycle_counter := trace_cycle_counter + 1.U
  } .otherwise {
    trace_cycle_counter := trace_cycle_counter
  }
  val wide_cycles_counter = RegInit(0.U((outgoingPCISdat.io.enq.bits.getWidth).W))
  wide_cycles_counter := trace_cycle_counter << (outgoingPCISdat.io.enq.bits.getWidth - 64)

   //target instruction type trigger (trigger through target software)
  //can configure the trigger instruction type externally though simulation driver
  val hostTriggerStartInst = RegInit(0.U((io.hPort.hBits.traces(0)(0).insn.getWidth).W)) 
  val hostTriggerStartInstMask = RegInit(0.U((io.hPort.hBits.traces(0)(0).insn.getWidth).W)) 
  attach(hostTriggerStartInst, "hostTriggerStartInst", WriteOnly)
  attach(hostTriggerStartInstMask, "hostTriggerStartInstMask", WriteOnly)

  val hostTriggerEndInst = RegInit(0.U((io.hPort.hBits.traces(0)(0).insn.getWidth).W)) 
  val hostTriggerEndInstMask = RegInit(0.U((io.hPort.hBits.traces(0)(0).insn.getWidth).W)) 
  attach(hostTriggerEndInst, "hostTriggerEndInst", WriteOnly)
  attach(hostTriggerEndInstMask, "hostTriggerEndInstMask", WriteOnly)

  //trigger selector
  val triggerSelector = RegInit(0.U((p(CtrlNastiKey).dataBits).W))
  attach(triggerSelector, "triggerSelector", WriteOnly) 

  //set the trigger
  //assert(triggerCycleCountEnd >= triggerCycleCountStart)
  val triggerCycleCountVal = RegInit(false.B) 
  triggerCycleCountVal := (trace_cycle_counter >= triggerCycleCountStart) & (trace_cycle_counter <= triggerCycleCountEnd)

  val triggerPCValVec = RegInit(VecInit(Seq.fill(io.hPort.hBits.traces.length)(false.B)))
  for (i <- 0 until io.hPort.hBits.traces.length) {
    when (io.hPort.hBits.traces(i)(0).valid) {
      when (triggerPCStart === io.hPort.hBits.traces(i)(0).iaddr) {
        triggerPCValVec(i) := true.B
      } .elsewhen ((triggerPCEnd === io.hPort.hBits.traces(i)(0).iaddr) && triggerPCValVec(i)) {
        triggerPCValVec(i) := false.B
      }
    }
  }

  val triggerInstValVec = RegInit(VecInit(Seq.fill(io.hPort.hBits.traces.length)(false.B)))
  for (i <- 0 until io.hPort.hBits.traces.length) {
    when (io.hPort.hBits.traces(i)(0).valid) {
      when (hostTriggerStartInst === (io.hPort.hBits.traces(i)(0).insn & hostTriggerStartInstMask)) {
        triggerInstValVec(i) := true.B
      } .elsewhen (hostTriggerEndInst === (io.hPort.hBits.traces(i)(0).insn & hostTriggerEndInstMask)) {
        triggerInstValVec(i) := false.B
      }
    }
  }

  val trigger = MuxLookup(triggerSelector, false.B, Seq(
    0.U -> true.B,
    1.U -> triggerCycleCountVal,
    2.U -> triggerPCValVec.reduce(_ || _),
    3.U -> triggerInstValVec.reduce(_ || _)))

  //TODO: for inter-widget triggering
  //io.trigger_out.head <> trigger

  // DMA mixin parameters
  lazy val toHostCPUQueueDepth  = TOKEN_QUEUE_DEPTH
  lazy val dmaSize = BigInt((BIG_TOKEN_WIDTH / 8) * TOKEN_QUEUE_DEPTH)

  val uint_traces = io.hPort.hBits.traces map (trace => (UInt(0, 64.W) | Cat(trace(0).valid, trace(0).iaddr)))
  //val uint_traces = hPort.hBits.traces map (trace => trace.asUInt)
  outgoingPCISdat.io.enq.bits := wide_cycles_counter | Cat(uint_traces)

  val tFireHelper = DecoupledHelper(outgoingPCISdat.io.enq.ready,
    hPort.toHost.hValid, hPort.fromHost.hReady)

  hPort.fromHost.hValid := tFireHelper.fire(hPort.fromHost.hReady)
  hPort.toHost.hReady := tFireHelper.fire

  outgoingPCISdat.io.enq.valid := tFireHelper.fire(outgoingPCISdat.io.enq.ready, trigger)

  // This need to go on a debug switch
  //when (outgoingPCISdat.io.enq.fire()) {
  //  hPort.hBits.traces.zipWithIndex.foreach({ case (bundle, bIdx) =>
  //    printf("Tile %d Trace Bundle\n", bIdx.U)
  //    bundle.zipWithIndex.foreach({ case (insn, insnIdx) =>
  //      printf(p"insn ${insnIdx}: ${insn}\n")
  //      //printf(b"insn ${insnIdx}, valid: ${insn.valid}")
  //      //printf(b"insn ${insnIdx}, iaddr: ${insn.iaddr}")
  //      //printf(b"insn ${insnIdx}, insn: ${insn.insn}")
  //      //printf(b"insn ${insnIdx}, priv:  ${insn.priv}")
  //      //printf(b"insn ${insnIdx}, exception: ${insn.exception}")
  //      //printf(b"insn ${insnIdx}, interrupt: ${insn.interrupt}")
  //      //printf(b"insn ${insnIdx}, cause: ${insn.cause}")
  //      //printf(b"insn ${insnIdx}, tval: ${insn.tval}")
  //    })
  //  })
  //}
  attach(outgoingPCISdat.io.deq.valid && !outgoingPCISdat.io.enq.ready, "tracequeuefull", ReadOnly)
  genCRFile()
}
