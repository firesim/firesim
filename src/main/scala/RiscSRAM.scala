package DebugMachine

import Chisel._

class RiscSRAM extends Module {
  val io = new Bundle {
    val isWr   = Bool(INPUT)
    val wrAddr = UInt(INPUT, 8)
    val wrData = Bits(INPUT, 32)
    val boot   = Bool(INPUT)
    val valid  = Bool(OUTPUT)
    val out    = Bits(OUTPUT, 32)
  }
  val file = Mem(Bits(width = 32), 16 /*256*/, seqRead = true)
  val code = Mem(Bits(width = 32), 16 /*256*/, seqRead = true)

  val add_op :: imm_op :: Nil = Enum(Bits(), 2)

  val pc       = Reg(UInt(width=8))
  val codeData = Reg(Bits(width=32))
  val fileAddr = Reg(UInt(width=8))
  val raData   = Reg(Bits(width=32))
  val rbData   = Reg(Bits(width=32))

  val inst = code(pc)
  val op   = inst(31,24)
  val rci  = inst(23,16)
  val rai  = inst(15, 8)
  val rbi  = inst( 7, 0)

  debug(op)
  debug(rci)
  debug(rai)
  debug(rbi)

  val ra = Mux(rai === Bits(0), Bits(0), raData)
  val rb = Mux(rbi === Bits(0), Bits(0), rbData)
  val rc = Bits(width = 32)

  val file_init :: ra_read :: rb_read :: rc_write :: Nil = Enum(Bits(), 4)
  val code_read :: code_write :: Nil = Enum(Bits(), 2)
  val fileState = Reg(init=file_init)
  val codeState = Reg(init=code_write)

  io.valid := Bool(false)
  io.out   := Bits(0)
  rc       := Bits(0)

  switch(codeState) {
    is(code_write) {
      when(io.isWr) {
        code(io.wrAddr) := io.wrData
      }. elsewhen(io.boot) {
        pc        := UInt(0)
        codeState := code_read
        fileState := file_init
      }
    }
    is(code_read) {
      switch(op) {
        is(add_op) { rc := ra + rb }
        is(imm_op) { rc := rai << UInt(8) | rbi }
      }
      io.out := rc
      switch(fileState) {
        is(file_init) {
          fileAddr := rai
          when(op === add_op) {
            fileState := ra_read
          }.otherwise {
            fileState := rc_write
          }
        }
        is(ra_read) {
          raData    := file(fileAddr)
          fileAddr  := rbi
          fileState := rb_read
        }
        is(rb_read) {
          rbData    := file(fileAddr)
          fileState := rc_write 
        }
        is(rc_write) {
          when(rci === UInt(255)) {
            io.valid  := Bool(true) 
          }.otherwise {
            file(rci) := rc
          }
          pc := pc + UInt(1)
          fileState := file_init
        }
      }
    }
  }
}

class RiscSRAMTests(c: RiscSRAM) extends Tester(c) {  
  def wr(addr: UInt, data: UInt)  = {
    poke(c.io.isWr,   1)
    poke(c.io.wrAddr, addr.litValue())
    poke(c.io.wrData, data.litValue())
    step(1)
  }
  def boot()  = {
    poke(c.io.isWr, 0)
    poke(c.io.boot, 1)
    step(1)
  }
  def tick()  = {
    poke(c.io.isWr, 0)
    poke(c.io.boot, 0)
    step(1)
  }
  def I (op: UInt, rc: Int, ra: Int, rb: Int) = 
    Cat(op, UInt(rc, 8), UInt(ra, 8), UInt(rb, 8))
  val app  = Array(
    I(c.imm_op,   1, 0, 1), // r1 <- 1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 2), // r1 <- 2
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 3), // r1 <- 3
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 4), // r1 <- 4
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 5), // r1 <- 5
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 6), // r1 <- 6
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 7), // r1 <- 7
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 8), // r1 <- 8
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 9), // r1 <- 9
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   1, 0, 10), // r1 <- 10
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 1), // r1 <- 1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 2), // r1 <- 2
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 3), // r1 <- 3
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 4), // r1 <- 4
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 5), // r1 <- 5
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 6), // r1 <- 6
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 7), // r1 <- 7
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 8), // r1 <- 8
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 9), // r1 <- 9
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.imm_op,   2, 0, 10), // r1 <- 10
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.add_op, 255, 1, 0)) // rh <- r1
  wr(UInt(0), Bits(0)) // skip reset
  for (addr <- 0 until app.length) 
    wr(UInt(addr), app(addr))
  boot()
  var k = 0
  do {
    tick(); k += 1
  } while (peek(c.io.valid) == 0 && k < 400)
  expect(k < 400, "TIME LIMIT")
  expect(c.io.out, 40)
}

class RiscSRAMDaisyTests(c: DaisyWrapper[RiscSRAM]) extends DaisyTester(c/*, isTrace = false*/) {  
  def wr(addr: UInt, data: UInt)  = {
    poke(c.target.io.isWr,   1)
    poke(c.target.io.wrAddr, addr.litValue())
    poke(c.target.io.wrData, data.litValue())
    step(1)
  }
  def boot()  = {
    poke(c.target.io.isWr, 0)
    poke(c.target.io.boot, 1)
    step(1)
  }
  def tick()  = {
    poke(c.target.io.isWr, 0)
    poke(c.target.io.boot, 0)
    step(1)
  }
  def I (op: UInt, rc: Int, ra: Int, rb: Int) = 
    Cat(op, UInt(rc, 8), UInt(ra, 8), UInt(rb, 8))
  val app  = Array(
    I(c.target.imm_op,   1, 0, 1), // r1 <- 1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    /*
    I(c.target.imm_op,   1, 0, 2), // r1 <- 2
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   1, 0, 3), // r1 <- 3
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   1, 0, 4), // r1 <- 4
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   1, 0, 5), // r1 <- 5
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   1, 0, 6), // r1 <- 6
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   1, 0, 7), // r1 <- 7
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   1, 0, 8), // r1 <- 8
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   1, 0, 9), // r1 <- 9
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   1, 0, 10), // r1 <- 10
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 1), // r1 <- 1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 2), // r1 <- 2
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 3), // r1 <- 3
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 4), // r1 <- 4
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 5), // r1 <- 5
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 6), // r1 <- 6
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 7), // r1 <- 7
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 8), // r1 <- 8
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 9), // r1 <- 9
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.imm_op,   2, 0, 10), // r1 <- 10
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    I(c.target.add_op,   2, 2, 1), // r1 <- r1 + r1
    */
    I(c.target.add_op, 255, 1, 0)) // rh <- r1
  wr(UInt(0), Bits(0)) // skip reset
  for (addr <- 0 until app.length) 
    wr(UInt(addr), app(addr))
  boot()
  var k = 0
  do {
    tick(); k += 1
  } while (peek(c.target.io.valid) == 0 && k < 40)
  expect(k < 40, "TIME LIMIT")
  expect(c.target.io.out, /*40*/ 4)
}
