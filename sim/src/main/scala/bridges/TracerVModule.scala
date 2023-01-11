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
  val insns = Input(Vec(numInsns, new DeclockedTracedInstruction(insnWidths)))
}

class TracerVDUT(implicit val p: Parameters) extends Module {
  // val insnCount = 8
  // val insnWidth = 48
  // val insnWidths = TracedInstructionWidths(insnWidth, 32, None, 1, 1)

  val insnCount  = 7
  val insnWidth  = 40
  val insnWidths = TracedInstructionWidths(insnWidth, insnCount, None, 64, 40)

  val io = IO(new TracerVDUTIO(insnWidths, insnCount))

  val tracerV = TracerVBridge(insnWidths, insnCount) // uncommenting this prevents peek poke from advancing
  tracerV.io.trace.insns := io.insns
}

class TracerVModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new TracerVDUT)
