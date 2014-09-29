package faee

import Chisel._
import TutorialExamples._
import scala.collection.mutable.{Stack => ScalaStack}

class GCDDaisyTests(c: DaisyWrapper[GCD]) extends DaisyTester(c) {
  val (a, b, z) = (64, 48, 16)
  do {
    val first = if (t == 0) 1 else 0
    poke(c.target.io.a, a)
    poke(c.target.io.b, b)
    poke(c.target.io.e, first)
    step(1)
  } while (t <= 1 || peek(c.target.io.v) == 0)
  expect(c.target.io.z, z)
}

class ParityDaisyTests(c: DaisyWrapper[Parity]) extends DaisyTester(c) {
  var isOdd = 0
  for (t <- 0 until 10) {
    val bit = rnd.nextInt(2)
    poke(c.target.io.in, bit)
    step(1)
    isOdd = (isOdd + bit) % 2;
    expect(c.target.io.out, isOdd)
  }
}

class StackDaisyTests(c: DaisyWrapper[Stack]) extends DaisyTester(c) {  
  var nxtDataOut = 0
  var dataOut = 0
  val stack = new ScalaStack[Int]()

  for (t <- 0 until 16) {
    val enable  = rnd.nextInt(2)
    val push    = rnd.nextInt(2)
    val pop     = rnd.nextInt(2)
    val dataIn  = rnd.nextInt(256)

    if (enable == 1) {
      dataOut = nxtDataOut
      if (push == 1 && stack.length < c.target.depth) {
        stack.push(dataIn)
      } else if (pop == 1 && stack.length > 0) {
        stack.pop()
      }
      if (stack.length > 0) {
        nxtDataOut = stack.top
      }
    }

    poke(c.target.io.pop,    pop)
    poke(c.target.io.push,   push)
    poke(c.target.io.en,     enable)
    poke(c.target.io.dataIn, dataIn)
    step(1)
    expect(c.target.io.dataOut, dataOut)
  }
}

class ShiftRegisterDaisyTests(c: DaisyWrapper[ShiftRegister]) extends DaisyTester(c) {  
  val reg     = Array.fill(4){ 0 }
  for (t <- 0 until 64) {
    val in = rnd.nextInt(2)
    poke(c.target.io.in, in)
    step(1)
    for (i <- 3 to 1 by -1)
      reg(i) = reg(i-1)
    reg(0) = in
    if (t >= 4) expect(c.target.io.out, reg(3))
  }
}

class EnableShiftRegisterDaisyTests(c: DaisyWrapper[EnableShiftRegister]) extends DaisyTester(c) {  
  val reg = Array.fill(4){ 0 }
  for (t <- 0 until 16) {
    val in    = rnd.nextInt(2)
    val shift = rnd.nextInt(2)
    poke(c.target.io.in,    in)
    poke(c.target.io.shift, shift)
    step(1)
    if (shift == 1) {
      for (i <- 3 to 1 by -1)
        reg(i) = reg(i-1)
      reg(0) = in
    }
    expect(c.target.io.out, reg(3))
  }
}

class MemorySearchDaisyTests(c: DaisyWrapper[MemorySearch]) extends DaisyTester(c) {
  val list = c.target.elts.map(int(_)) 
  val n = 8
  val maxT = n * (list.length + 3)
  for (k <- 0 until n) {
    val target = rnd.nextInt(16)
    poke(c.target.io.en,     1)
    poke(c.target.io.target, target)
    step(1)
    poke(c.target.io.en,     0)
    do {
      step(1)
    } while (peek(c.target.io.done) == 0 && t < maxT)
    val addr = peek(c.target.io.address).toInt
    expect(addr == list.length || list(addr) == target, 
           "LOOKING FOR " + target + " FOUND " + addr)
  }
}

class ResetShiftRegisterDaisyTests(c: DaisyWrapper[ResetShiftRegister]) extends DaisyTester(c) {  
  val ins = Array.fill(5){ 0 }
  var k   = 0
  for (n <- 0 until 16) {
    val in    = rnd.nextInt(2)
    val shift = rnd.nextInt(2)
    if (shift == 1) 
      ins(k % 5) = in
    poke(c.target.io.in,    in)
    poke(c.target.io.shift, shift)
    step(1)
    if (shift == 1)
      k = k + 1
    expect(c.target.io.out, (if (n < 4) 0 else ins((k + 1) % 5)))
  }
}

class RiscDaisyTests(c: DaisyWrapper[Risc]) extends DaisyTester(c) {  
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
  val app  = Array(I(c.target.imm_op,   1, 0, 1), // r1 <- 1
                   I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
                   I(c.target.add_op,   1, 1, 1), // r1 <- r1 + r1
                   I(c.target.add_op, 255, 1, 0)) // rh <- r1
  wr(UInt(0), Bits(0)) // skip reset
  for (addr <- 0 until app.length) 
    wr(UInt(addr), app(addr))
  boot()
  var k = 0
  do {
    tick(); k += 1
  } while (peek(c.target.io.valid) == 0 && k < 10)
  expect(k < 10, "TIME LIMIT")
  expect(c.target.io.out, 4)
}

class RouterDaisyTests(c: DaisyWrapper[Router]) extends DaisyTester(c) {  
  def rd(addr: Int, data: Int) = {
    poke(c.target.io.in.valid,        0)
    poke(c.target.io.writes.valid,    0)
    poke(c.target.io.reads.valid,     1)
    poke(c.target.io.replies.ready,   1)
    poke(c.target.io.reads.bits.addr, addr)
    step(1)
    expect(c.target.io.replies.bits, data)
  }
  def wr(addr: Int, data: Int)  = {
    poke(c.target.io.in.valid,         0)
    poke(c.target.io.reads.valid,      0)
    poke(c.target.io.writes.valid,     1)
    poke(c.target.io.writes.bits.addr, addr)
    poke(c.target.io.writes.bits.data, data)
    step(1)
  }
  def isAnyValidOuts(): Boolean = {
    for (out <- c.target.io.outs)
      if (peek(out.valid) == 1)
        return true
    false
  }
  def rt(header: Int, body: Int)  = {
    for (out <- c.target.io.outs)
      poke(out.ready, 1)
    poke(c.target.io.reads.valid,    0)
    poke(c.target.io.writes.valid,   0)
    poke(c.target.io.in.valid,       1)
    poke(c.target.io.in.bits.header, header)
    poke(c.target.io.in.bits.body,   body)
    var i = 0
    do {
      step(1)
      i += 1
    } while (!isAnyValidOuts() || i > 10)
    expect(i < 10, "FIND VALID OUT")
  }
  rd(0, 0)
  wr(0, 1)
  rd(0, 1)
  rt(0, 1)
}
