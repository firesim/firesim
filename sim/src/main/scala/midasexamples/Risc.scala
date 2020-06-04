//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import chisel3.util._

class RiscDUT extends Module {
  val io = IO(new Bundle {
    val isWr   = Input(Bool())
    val wrAddr = Input(UInt(8.W))
    val wrData = Input(UInt(32.W))
    val boot   = Input(Bool())
    val valid  = Output(Bool())
    val out    = Output(UInt(32.W))
  })
  val file = Mem(256, UInt(32.W))
  val code = Mem(256, UInt(32.W))
  val pc   = RegInit(0.U(8.W))

  val add_op :: imm_op :: Nil = Enum(2)

  val inst = code(pc)
  val op   = inst(31,24)
  val rci  = inst(23,16)
  val rai  = inst(15, 8)
  val rbi  = inst( 7, 0)

  val ra = Mux(rai === 0.U, 0.U, file(rai))
  val rb = Mux(rbi === 0.U, 0.U, file(rbi))
  val rc = Wire(UInt(32.W))

  io.valid := false.B
  io.out   := 0.U
  rc       := 0.U

  when (io.isWr) {
    code(io.wrAddr) := io.wrData
  } .elsewhen (io.boot) {
    pc := 0.U
  } .otherwise {
    switch(op) {
      is(add_op) { rc := ra + rb }
      is(imm_op) { rc := (rai << 8.U) | rbi }
    }
    io.out := rc
    when (rci === 255.U) {
      io.valid := true.B
    } .otherwise {
      file(rci) := rc
    }
    pc := pc + 1.U
  }
}

class Risc(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new RiscDUT)
