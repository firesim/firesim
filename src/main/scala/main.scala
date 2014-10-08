package DebugMachine

import Chisel._
import TutorialExamples._

object DebugMachine {
  def main(args: Array[String]) {
    val chiselArgs = args.slice(1, args.length)
    val res = args(0) match {
      case "RiscSRAM" =>
        chiselMainTest(chiselArgs, () => Module(new RiscSRAM))(
          c => new RiscSRAMTests(c))
      case "RiscSRAMWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new RiscSRAM))(
          c => new RiscSRAMDaisyTests(c))
      case "RiscWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new Risc))(
          c => new RiscDaisyTests(c))
      case "GCDWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new GCD))(
          c => new GCDDaisyTests(c))
      case "ParityWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new Parity))(
          c => new ParityDaisyTests(c))
      case "StackWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new Stack(8)))(
          c => new StackDaisyTests(c))
      case "RouterWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new Router))(
          c => new RouterDaisyTests(c))
      case "ShiftRegisterWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new ShiftRegister))(
          c => new ShiftRegisterDaisyTests(c))
      case "ResetShiftRegisterWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new ResetShiftRegister))(
          c => new ResetShiftRegisterDaisyTests(c))
      case "EnableShiftRegisterWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new EnableShiftRegister))(
          c => new EnableShiftRegisterDaisyTests(c))
      case "MemorySearchWrapper" =>
        chiselMainTest(chiselArgs, () => DaisyWrapper(new MemorySearch))(
          c => new MemorySearchDaisyTests(c))
      case _ =>
    }
  }
}
