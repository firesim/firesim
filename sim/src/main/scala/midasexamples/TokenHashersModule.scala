//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters

class TokenHashersModuleIO() extends Bundle {
  val writeValue       = Input(UInt((32).W))
  val readValue        = Output(UInt((32).W))
  val readValueFlipped = Output(UInt((32).W))
}

/** A DUT to demonstrate usage of a TokenHashers. A single input is looped back both as unmodified, and bit-flipped
  * versions. [[TutorialSuite.scala]]
  */
class TokenHashersDUT(implicit val p: Parameters) extends Module {
  val io = IO(new TokenHashersModuleIO())

  // simple loopback
  io.readValue := io.writeValue

  // Note that we only invert bits if any single bit is set in writeValue
  // this makes the test a bit more simple from the C side
  when(io.writeValue.orR === true.B) {
    io.readValueFlipped := ~io.writeValue
  }.otherwise {
    io.readValueFlipped := 0.U
  }
}

class TokenHashersModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new TokenHashersDUT)
