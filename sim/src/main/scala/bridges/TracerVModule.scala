//See LICENSE for license details.

package firesim.bridges

import chisel3._

import firesim.midasexamples.PeekPokeMidasExampleHarness
import freechips.rocketchip.config.Parameters
import testchipip.DeclockedTracedInstruction
import testchipip.TracedInstructionWidths
import midas.targetutils.TriggerSink

class TracerVDUTIO(insnWidths: TracedInstructionWidths, numInsns: Int) extends Bundle {
  val triggerSink = Output(Bool())
  val insns       = Input(Vec(numInsns, new DeclockedTracedInstruction(insnWidths)))
}

class TracerVDUT(implicit val p: Parameters) extends Module {
  val insnCount = 8
  val insnWidth = 48
  val insnWidths = TracedInstructionWidths(insnWidth, 32, None, 1, 1)

  val io = IO(new TracerVDUTIO(insnWidths, insnCount))

  val tracerV = TracerVBridge(insnWidths, insnCount)
  tracerV.io.trace.insns := io.insns
  TriggerSink(io.triggerSink)
}

class TracerVModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new TracerVDUT)