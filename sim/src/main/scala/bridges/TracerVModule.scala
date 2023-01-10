//See LICENSE for license details.

package firesim.bridges

import chisel3._

import firesim.midasexamples.PeekPokeMidasExampleHarness
import freechips.rocketchip.config.Parameters
import testchipip.DeclockedTracedInstruction
import testchipip.TracedInstructionWidths
import midas.targetutils.TriggerSink
import testchipip.TileTraceIO

class TracerVDUTIO(insnWidths: TracedInstructionWidths, numInsns: Int) extends Bundle {
  // val triggerSink = Output(Bool())
  val insns       = Input(Vec(numInsns, new DeclockedTracedInstruction(insnWidths)))
  val tracervdebug       = Input(UInt(32.W))
}

class TracerVDUT(implicit val p: Parameters) extends Module {
  // val insnCount = 8
  // val insnWidth = 48
  // val insnWidths = TracedInstructionWidths(insnWidth, 32, None, 1, 1)
  
  val insnCount = 4
  val insnWidth = 40
  val insnWidths = TracedInstructionWidths(insnWidth,32,None,64,40)


  val io = IO(new TracerVDUTIO(insnWidths, insnCount))

  val d2 = RegInit(0.U(32.W))
  when(true.B) {
    d2 := io.tracervdebug
  }

  dontTouch(d2);
  dontTouch(io);

  // val fatWire = Wire(new TileTraceIO(insnWidths, insnCount))
  // fatWire.clock := tileTrace.clock
  // fatWire.reset := tileTrace.reset
  // fatWire.insns := VecInit(Seq.fill(32) {tileTrace.insns.head} )
  

  // val tracerV = TracerVBridge(fatWire)
  val tracerV = TracerVBridge(insnWidths, insnCount) // uncommenting this prevents peek poke from advancing
  tracerV.io.trace.insns := io.insns

  // TriggerSink(io.triggerSink)
}

class TracerVModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new TracerVDUT)