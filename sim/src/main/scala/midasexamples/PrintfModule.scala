//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util.random.LFSR
import org.chipsalliance.cde.config.Parameters

import midas.targetutils.SynthesizePrintf

/** An example module that includes a series of printfs to be synthesized.
  *
  * In tests, the synthesized printf stream is diffed against the native printf output from verilator.
  *
  * @param printfPrefix
  *   Used to disambiguate printfs generated by different instances of this module.
  */
class PrintfModuleDUT(printfPrefix: String = "SYNTHESIZED_PRINT ") extends Module {
  val io = IO(new Bundle {
    val a = Input(Bool())
    val b = Input(Bool())
  })

  // Rely on zero-initialization
  val cycle = Reg(UInt(16.W))
  cycle := cycle + 1.U

  // Printf format strings must be prefixed with "SYNTHESIZED_PRINT CYCLE: %d"
  // so they can be pulled out of RTL simulators log and sorted within a cycle
  // As the printf order will be different betwen RTL simulator and synthesized stream
  SynthesizePrintf(printf(s"${printfPrefix}CYCLE: %d\n", cycle))

  val wideArgument = VecInit(Seq.fill(33)(WireInit(cycle))).asUInt
  SynthesizePrintf(
    printf(s"${printfPrefix}CYCLE: %d wideArgument: %x\n", cycle, wideArgument)
  ) // argument width > DMA width

  val childInst = Module(new PrintfModuleChild(printfPrefix))
  childInst.c     := io.a
  childInst.cycle := cycle

  val ch = Mux(cycle(7, 0) > 32.U && cycle(7, 0) < 127.U, cycle(7, 0), 32.U(8.W))
  SynthesizePrintf(printf(s"${printfPrefix}CYCLE: %d Char: %c\n", cycle, ch))
}

class PrintfModuleChild(printfPrefix: String) extends Module {
  val c     = IO(Input(Bool()))
  val cycle = IO(Input(UInt(16.W)))

  val lfsr = LFSR(16, c)
  SynthesizePrintf(printf(s"${printfPrefix}CYCLE: %d LFSR: %x\n", cycle, lfsr))

  //when (lsfr(0)) {
  //  SynthesizePrintf(printf(p"SYNTHESIZED_PRINT CYCLE: ${cycle} LFSR is odd"))
  //}
}

class PrintfModule(implicit p: Parameters) extends firesim.lib.testutils.PeekPokeHarness(() => new PrintfModuleDUT)

class NarrowPrintfModuleDUT extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
  })

  val cycle = Reg(UInt(12.W))
  cycle := cycle + 1.U
  when(LFSR(16)(0) & LFSR(16)(0) & io.enable) {
    SynthesizePrintf(printf("SYNTHESIZED_PRINT CYCLE: %d\n", cycle(5, 0)))
  }
}

class NarrowPrintfModule(implicit p: Parameters)
    extends firesim.lib.testutils.PeekPokeHarness(() => new NarrowPrintfModuleDUT)
