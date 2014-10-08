package faee

import Chisel._

abstract class DaisyChainIO(datawidth: Int, daisywidth: Int) extends Bundle {
  val stall = Bool(INPUT)
  val data =  UInt(INPUT, datawidth)
  val in  = Decoupled(UInt(INPUT, daisywidth)).flip
  val out = Decoupled(UInt(INPUT, daisywidth))
}

abstract class DaisyChain(datawidth: Int, daisywidth: Int) extends Module {
  def io: DaisyChainIO
  def copyCond: Bool
  def readCond: Bool
  val daisylen = (datawidth - 1) / daisywidth + 1
  val regs = Vec.fill(daisylen) { Reg(UInt(width=daisywidth)) }
  val counter = Reg(UInt(width=log2Up(daisylen+1)))

  def initChain(fake: Int = 0) {
    // Daisy chain datapath
    io.out.bits := regs(daisylen-1)
    io.out.valid := counter.orR // && io.out.ready
    io.in.ready := io.out.ready
    var high = datawidth-1 
    for (i <- (0 until daisylen).reverse) {
      val low = math.max(high-daisywidth+1, 0)
      val padwidth = daisywidth-(high-low+1)
      when(copyCond) {
        if (padwidth > 0)
          regs(i) := Cat(io.data(high, low), UInt(0, padwidth))
        else 
          regs(i) := io.data(high, low)
      }
      when(readCond && counter.orR && io.out.fire()) {
        if (i == 0)
          regs(i) := io.in.bits
        else
          regs(i) := regs(i-1)
      }
      high -= daisywidth
    }

    // Daisy chain control logic
    when(copyCond) {
      counter := UInt(daisylen)
    }
    when(readCond && counter.orR && io.out.fire() && !io.in.valid) {
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
  val readCond = io.stall && copied 
  initChain()
}

class SRAMChainIO(n: Int, datawidth: Int, daisywidth: Int) extends 
  DaisyChainIO(datawidth, daisywidth) {
  val restart = Bool(INPUT)
  val addrIn = UInt(INPUT, width=log2Up(n))
  val addrOut = Valid(UInt(width=log2Up(n)))
}

class SRAMChain(n: Int, datawidth: Int, daisywidth: Int) extends 
  DaisyChain(datawidth, daisywidth) {
  val io = new SRAMChainIO(n, datawidth, daisywidth)

  val s_IDLE :: s_ADDRGEN :: s_MEMREAD :: s_DONE :: Nil = Enum(UInt(), 4)
  val addrState = Reg(init=s_IDLE)
  val copyCond = addrState === s_MEMREAD
  val readCond = addrState === s_DONE
  val addrIn = Reg(UInt(width=log2Up(n)))
  val addrOut = Reg(UInt(width=log2Up(n)))
  initChain()
  
  io.addrOut.bits := addrIn
  io.addrOut.valid := Bool(false)

  // SRAM control
  switch(addrState) {
    is(s_IDLE) {
      addrIn := io.addrIn
      addrOut := UInt(0)
      when(io.stall) {
        addrState := s_DONE
      }
    }
    is(s_ADDRGEN) {
      addrState := s_MEMREAD
      io.addrOut.bits := addrOut
      io.addrOut.valid := Bool(true)
    }
    is(s_MEMREAD) {
      addrState := s_DONE
      addrOut   := addrOut + UInt(1)
    }
    is(s_DONE) {
      when(io.restart) {
        addrState := s_ADDRGEN
      }
      when(!io.stall) {
        addrState := s_IDLE
      }
      io.addrOut.bits := addrIn
      io.addrOut.valid := io.stall
    }
  }
}  
