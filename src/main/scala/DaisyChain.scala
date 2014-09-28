package faee

import Chisel._

abstract class DaisyChainIO(datawidth: Int, daisywidth: Int) extends Bundle {
  val stall = Bool(INPUT)
  val data =  UInt(INPUT, datawidth)
  val in  = Valid(UInt(INPUT, daisywidth)).flip
  val out = Decoupled(UInt(INPUT, daisywidth))
}

abstract class DaisyChain(datawidth: Int, daisywidth: Int = 1) extends Module {
  val regs = Vec.fill(datawidth) { Reg(UInt(width=daisywidth)) }
  def io: DaisyChainIO
  val counter = Reg(UInt(width=log2Up(datawidth+1)))
  def copied: Bool
  def copyCond: Bool
  def readCond: Bool
  // val readCond = io.stall && copied && counter.orR && io.out.fire()

  def init {
    // Daisy chain datapath
    io.out.bits := regs(datawidth-1)
    io.out.valid := counter.orR && io.out.ready
    for (i <- 0 until datawidth) {
      when(copyCond) {
        regs(i) := io.data(i)
      }
      when(readCond) {
        if (i == 0)
          regs(i) := io.in.bits
        else
          regs(i) := regs(i-1)
      }
    }

    // Daisy chain control logic
    when(copyCond) {
      counter := UInt(datawidth)
    }
    when(readCond && !io.in.valid) {
      counter := counter - UInt(1)
    }
  }
}

class StateChainIO(datawidth: Int, daisywidth: Int = 1) extends 
  DaisyChainIO(datawidth, daisywidth)

class StateChain(datawidth: Int, daisywidth: Int = 1) extends 
  DaisyChain(datawidth, daisywidth) {
  val io = new StateChainIO(datawidth, daisywidth)
  val copied = Reg(next=io.stall)
  val copyCond = io.stall && !copied
  val readCond = io.stall && copied && counter.orR && io.out.fire()
  init
}

class SRAMChainIO(n: Int, datawidth: Int, daisywidth: Int = 1) extends 
  DaisyChainIO(datawidth, daisywidth) {
  val addr = Valid(UInt(width=log2Up(n)))
  val restart = Bool(INPUT)
}

class SRAMChain(n: Int, datawidth: Int, daisywidth: Int = 1) extends 
  DaisyChain(datawidth, daisywidth) {
  val io = new SRAMChainIO(n, datawidth, daisywidth)
  val copied = Reg(Bool())
  val copyCond = Reg(Bool())
  val readCond = io.stall && copied && counter.orR && io.out.fire()

  // SRAM control
  val s_idle :: s_addrgen :: s_memread :: Nil = Enum(UInt(), 3)
  val addrState = Reg(init = s_idle)
  val addr = Reg(UInt(width=log2Up(n)))
  io.addr.bits := addr
  io.addr.valid := addrState === s_addrgen
  switch(addrState) {
    is(s_idle) {
      when(io.stall && !copied) {
        when(addr < UInt(n)) {
          addr := addr + UInt(1)
          addrState := s_addrgen
        }.otherwise {
          addr := UInt(0)
          addrState := s_memread
        }
        copied := Bool(false)
      }
      // Read daisy chain again
      when(io.stall && io.restart && copied) {
        copied := Bool(false)
      }
      copyCond := Bool(false)
    }
    is(s_addrgen) {
      addrState := s_memread
      copyCond  := Bool(true)
      copied    := Bool(false)
    }
    is(s_memread) {
      addrState := s_idle
      copyCond  := Bool(false)
      copied    := Bool(true)
    }
  }
}  
