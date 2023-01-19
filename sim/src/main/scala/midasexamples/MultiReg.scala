// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.targetutils._

class PipeIO extends Bundle {
  val i = Input(UInt(32.W))
  val o = Output(UInt(32.W))
}

class RegPipe extends Module {
  val io = IO(new PipeIO)
  io.o := RegNext(io.i)
}

class MultiRegDUT extends Module {
  val nCopies = 4
  val io = IO(new Bundle {
    val pipeIOs = Vec(nCopies, new PipeIO)
  })
  val pipes = Seq.fill(nCopies)(Module(new RegPipe))
  (io.pipeIOs zip pipes).foreach {
    case (pio, p) =>
      p.io <> pio
      annotate(EnableModelMultiThreadingAnnotation(p))
  }
}

class MultiReg(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new MultiRegDUT)
