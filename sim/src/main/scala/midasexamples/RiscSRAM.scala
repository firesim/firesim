//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import chisel3.util._

class RiscSRAMDUT extends Module {
  val io = IO(new Bundle {
    val isWr   = Input(Bool())
    val wrAddr = Input(UInt(8.W))
    val wrData = Input(UInt(32.W))
    val boot   = Input(Bool())
    val valid  = Output(Bool())
    val out    = Output(UInt(32.W))
  })
  val fileMem = SyncReadMem(256, UInt(32.W))
  // We only support combinational mems for now
  //chisel3.experimental.annotate(MemModelAnnotation(fileMem))
  val codeMem = SyncReadMem(128, UInt(32.W))
  //chisel3.experimental.annotate(MemModelAnnotation(codeMem))

  val idle :: fetch :: decode :: ra_read :: rb_read :: rc_write :: Nil = Enum(6)
  val state = RegInit(idle)

  val add_op :: imm_op :: Nil = Enum(2)
  val pc       = RegInit(0.U(8.W))
  val raData   = Reg(UInt(32.W))
  val rbData   = Reg(UInt(32.W))

  val code = codeMem.read(pc, !io.isWr)
  when(io.isWr) {
    codeMem.write(io.wrAddr, io.wrData)
  }

  val inst = Reg(UInt(32.W))
  val op   = inst(31,24)
  val rci  = inst(23,16)
  val rai  = inst(15, 8)
  val rbi  = inst( 7, 0)
  val ra   = Mux(rai === 0.U, 0.U, raData)
  val rb   = Mux(rbi === 0.U, 0.U, rbData)

  io.out   := Mux(op === add_op, ra + rb, Cat(rai, rbi))
  io.valid := state === rc_write && rci === 255.U

  val file_wen = state === rc_write && rci =/= 255.U
  val file_addr = Mux(state === decode, rai, rbi)
  val file = fileMem.read(file_addr)//, !file_wen)
  when(file_wen) {
    fileMem.write(rci, io.out)
  }

  switch(state) {
    is(idle) {
      when(io.boot) {
        state := fetch
      }
    }
    is(fetch) {
      pc    := pc + 1.U
      inst  := code
      state := decode
    }
    is(decode) {
      state := Mux(op === add_op, ra_read, rc_write)
    }
    is(ra_read) {
      raData := file
      state  := rb_read
    }
    is(rb_read) {
      rbData := file
      state  := rc_write
    }
    is(rc_write) {
      when(!io.valid) {
        state := fetch
      }
    }
  }
}

class RiscSRAM(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new RiscSRAMDUT)
