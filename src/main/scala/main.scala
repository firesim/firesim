package DebugMachine

import Chisel._
import Daisy._
import Designs._
import TutorialExamples._
import mini.Core
import mini.Tile

object DebugMachine {
  def main(args: Array[String]) {
    val (chiselArgs, testArgs) = args.tail partition (_.head != '+')
    val res = args(0) match {
      case "RiscSRAM" =>
        chiselMainTest(chiselArgs, () => Module(new RiscSRAM))(
          c => new RiscSRAMTests(c))
      case "RiscSRAMShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new RiscSRAM))(
          c => new RiscSRAMDaisyTests(c))
      case "RiscShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new Risc))(
          c => new RiscDaisyTests(c))
      case "GCDShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new GCD))(
          c => new GCDDaisyTests(c))
      case "ParityShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new Parity))(
          c => new ParityDaisyTests(c))
      case "StackShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new Stack(8)))(
          c => new StackDaisyTests(c))
      case "RouterShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new Router))(
          c => new RouterDaisyTests(c))
      case "ShiftRegisterShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new ShiftRegister))(
          c => new ShiftRegisterDaisyTests(c))
      case "ResetShiftRegisterShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new ResetShiftRegister))(
          c => new ResetShiftRegisterDaisyTests(c))
      case "EnableShiftRegisterShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new EnableShiftRegister))(
          c => new EnableShiftRegisterDaisyTests(c))
      case "MemorySearchShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new MemorySearch))(
          c => new MemorySearchDaisyTests(c))
      case "FIR2DShim" =>
        chiselMainTest(chiselArgs, () => DaisyShim(new FIR2D(32, 8, 3)))(
          c => new FIR2DDaisyTests(c, 32, 8, 3))
      case "CoreShim" => 
        chiselMainTest(chiselArgs, () => DaisyShim(new Core, mini.Config.params))(
          c => new CoreDaisyTests(c, testArgs))
      case "TileShim" => 
        chiselMainTest(chiselArgs, () => DaisyShim(new Tile, mini.Config.params))(
          c => new TileDaisyTests(c, testArgs))
      case _ =>
    }
  }
}
