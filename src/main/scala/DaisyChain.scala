package DebugMachine

import Chisel._

case object Datawidth extends Field[Int]
case object SRAMSize extends Field[Int]

// Common structures for daisy chains
abstract trait DaisyChainParams extends UsesParameters {
  val datawidth = params(Datawidth)
  val daisywidth = params(Daisywidth)
  val daisylen = (datawidth - 1) / daisywidth + 1
}

class DataIO extends Bundle with DaisyChainParams {
  val in  = Decoupled(UInt(INPUT, daisywidth)).flip
  val out = Decoupled(UInt(INPUT, daisywidth))
  val data = UInt(INPUT, datawidth)
}

class CntrIO extends Bundle {
  val copyCond = Bool(OUTPUT)
  val readCond = Bool(OUTPUT)
  val cntrNotZero = Bool(OUTPUT)
  val outFire = Bool(INPUT)
  val inValid = Bool(INPUT)
}

class DaisyDatapathIO extends Bundle {
  val dataIo = new DataIO
  val ctrlIo = (new CntrIO).flip
}

class DaisyDatapath extends Module with DaisyChainParams { 
  val io = new DaisyDatapathIO
  val regs = Vec.fill(daisylen) { Reg(UInt(width=daisywidth)) }

  io.dataIo.out.bits := regs(daisylen-1)
  io.dataIo.out.valid := io.ctrlIo.cntrNotZero
  io.dataIo.in.ready := io.dataIo.out.ready
  io.ctrlIo.outFire := io.dataIo.out.fire()
  io.ctrlIo.inValid := io.dataIo.in.valid

  var high = datawidth-1 
  for (i <- (0 until daisylen).reverse) {
    val low = math.max(high-daisywidth+1, 0)
    val padwidth = daisywidth-(high-low+1)
    when(io.ctrlIo.copyCond) {
      if (padwidth > 0)
        regs(i) := Cat(io.dataIo.data(high, low), UInt(0, padwidth))
      else 
        regs(i) := io.dataIo.data(high, low)
    }
    when(io.ctrlIo.readCond && io.dataIo.out.fire()) {
      if (i == 0)
        regs(i) := io.dataIo.in.bits
      else
        regs(i) := regs(i-1)
    }
    high -= daisywidth
  }
}

class DaisyControlIO extends Bundle {
  val stall = Bool(INPUT)
  val ctrlIo = new CntrIO
}

class DaisyCounter(io: DaisyControlIO, daisylen: Int) {
  val cntr = Reg(UInt(width=log2Up(daisylen+1)))
  def isNotZero = cntr.orR

  // Daisy chain control logic
  when(io.ctrlIo.copyCond) {
    cntr := UInt(daisylen)
  }
  when(io.ctrlIo.readCond && io.ctrlIo.outFire && !io.ctrlIo.inValid) {
    cntr := cntr - UInt(1)
  }
}


// Define state daisy chains
class StateChainControlIO extends DaisyControlIO

class StateChainControl extends Module with DaisyChainParams {
  val io = new StateChainControlIO
  val copied = Reg(next=io.stall)
  val counter = new DaisyCounter(io, daisylen)
  
  io.ctrlIo.cntrNotZero := counter.isNotZero
  io.ctrlIo.copyCond := io.stall && !copied
  io.ctrlIo.readCond := io.stall && copied && counter.isNotZero
}

class StateChainIO extends Bundle {
  val stall = Bool(INPUT)
  val dataIo = new DataIO
}

class StateChain extends Module {
  val io = new StateChainIO
  val datapath = Module(new DaisyDatapath)
  val control = Module(new StateChainControl)

  io.stall <> control.io.stall
  io.dataIo <> datapath.io.dataIo
  control.io.ctrlIo <> datapath.io.ctrlIo
}


// Define sram daisy chains
abstract trait SRAMChainParams extends UsesParameters {
  val n = params(SRAMSize)
}

class AddrIO extends Bundle with SRAMChainParams {
  val in = UInt(INPUT, width=log2Up(n))
  val out = Valid(UInt(width=log2Up(n)))
}

class SRAMChainControlIO extends DaisyControlIO {
  val restart = Bool(INPUT)
  val addrIo = new AddrIO
}

class SRAMChainControl extends Module with DaisyChainParams with SRAMChainParams {
  val io = new SRAMChainControlIO
  val s_IDLE :: s_ADDRGEN :: s_MEMREAD :: s_DONE :: Nil = Enum(UInt(), 4)
  val addrState = Reg(init=s_IDLE)
  val addrIn = Reg(UInt(width=log2Up(n)))
  val addrOut = Reg(UInt(width=log2Up(n)))
  val counter = new DaisyCounter(io, daisylen)

  io.ctrlIo.cntrNotZero := counter.isNotZero
  io.ctrlIo.copyCond := addrState === s_MEMREAD
  io.ctrlIo.readCond := addrState === s_DONE && counter.isNotZero
  io.addrIo.out.bits := addrIn
  io.addrIo.out.valid := Bool(false)

  // SRAM control
  switch(addrState) {
    is(s_IDLE) {
      addrIn := io.addrIo.in
      addrOut := UInt(0)
      when(io.stall) {
        addrState := s_DONE
      }
    }
    is(s_ADDRGEN) {
      addrState := s_MEMREAD
      io.addrIo.out.bits := addrOut
      io.addrIo.out.valid := Bool(true)
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
      io.addrIo.out.bits := addrIn
      io.addrIo.out.valid := io.stall
    }
  }
}

class SRAMChainIO extends StateChainIO {
  val restart = Bool(INPUT)
  val addrIo = new AddrIO
}

class SRAMChain extends Module {
  val io = new SRAMChainIO
  val datapath = Module(new DaisyDatapath)
  val control = Module(new SRAMChainControl)

  io.stall <> control.io.stall
  io.restart <> control.io.restart
  io.dataIo <> datapath.io.dataIo
  io.addrIo <> control.io.addrIo
  control.io.ctrlIo <> datapath.io.ctrlIo
}
