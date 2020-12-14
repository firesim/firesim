//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util.random.LFSR
import freechips.rocketchip.config.Parameters

import midas.targetutils.SynthesizePrintf

class PrintfModuleDUT(printfPrefix: String = "SYNTHESIZED_PRINT ") extends Module {
  val io = IO(new Bundle {
    val a = Input(Bool())
    val b = Input(Bool())
  })

  val cycle = RegInit(0.U(16.W))
  cycle := cycle + 1.U

  // Printf format strings must be prefixed with "SYNTHESIZED_PRINT CYCLE: %d"
  // so they can be pulled out of RTL simulators log and sorted within a cycle
  // As the printf order will be different betwen RTL simulator and synthesized stream
  printf(SynthesizePrintf(s"${printfPrefix}CYCLE: %d\n", cycle))

  val wideArgument = VecInit(Seq.fill(33)(WireInit(cycle))).asUInt
  printf(SynthesizePrintf(s"${printfPrefix}CYCLE: %d wideArgument: %x\n", cycle, wideArgument)) // argument width > DMA width

  val childInst = Module(new PrintfModuleChild(printfPrefix))
  childInst.c := io.a
  childInst.cycle := cycle

  printf(SynthesizePrintf("thi$!sn+taS/\neName", s"${printfPrefix}CYCLE: %d constantArgument: %x\n", cycle, 1.U(8.W)))
}

class PrintfModuleChild(printfPrefix: String) extends MultiIOModule {
  val c = IO(Input(Bool()))
  val cycle = IO(Input(UInt(16.W)))

  val lfsr = LFSR(16, c)
  printf(SynthesizePrintf(s"${printfPrefix}CYCLE: %d LFSR: %x\n", cycle, lfsr))

  //when (lsfr(0)) {
  //  printf(SynthesizePrintf(p"SYNTHESIZED_PRINT CYCLE: ${cycle} LFSR is odd"))
  //}
}

class PrintfModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new PrintfModuleDUT)

class NarrowPrintfModuleDUT extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
  })

  val cycle = RegInit(0.U(12.W))
  cycle := cycle + 1.U
  when(LFSR(16)(0) & LFSR(16)(0) & io.enable) {
    printf(SynthesizePrintf("SYNTHESIZED_PRINT CYCLE: %d\n", cycle(5,0)))
  }
}

class NarrowPrintfModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new NarrowPrintfModuleDUT)
