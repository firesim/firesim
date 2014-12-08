package Daisy

import Chisel._

case object DataLen extends Field[Int]
case object SRAMSize extends Field[Int]

// Common structures for daisy chains
abstract trait DaisyChainParams extends UsesParameters {
  val dataLen = params(DataLen)
  val daisyLen = params(DaisyLen)
  val daisySize = (dataLen - 1) / daisyLen + 1
}

abstract class DaisyChainBundle extends Bundle with DaisyChainParams

class DataIO extends DaisyChainBundle {
  val in  = Decoupled(UInt(INPUT, daisyLen)).flip
  val out = Decoupled(UInt(INPUT, daisyLen))
  val data = Vec.fill(daisySize) { UInt(INPUT, daisyLen) }
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

abstract class DaisyChainModule extends Module with DaisyChainParams

class DaisyDatapath extends DaisyChainModule { 
  val io = new DaisyDatapathIO
  val regs = Vec.fill(daisySize) { Reg(UInt(width=daisyLen)) }

  io.dataIo.out.bits := regs(daisySize-1)
  io.dataIo.out.valid := io.ctrlIo.cntrNotZero
  io.dataIo.in.ready := io.dataIo.out.ready
  io.ctrlIo.outFire := io.dataIo.out.fire()
  io.ctrlIo.inValid := io.dataIo.in.valid

  val readCondAndOutFire = io.ctrlIo.readCond && io.dataIo.out.fire()
  for (i <- (0 until daisySize).reverse) {
    when(io.ctrlIo.copyCond) {
      regs(i) := io.dataIo.data(i)
    }
    when(readCondAndOutFire) {
      if (i == 0)
        regs(i) := io.dataIo.in.bits
      else
        regs(i) := regs(i-1)
    }
  }
}

class DaisyControlIO extends Bundle {
  val stall = Bool(INPUT)
  val ctrlIo = new CntrIO
}

class DaisyCounter(ctrlIo: CntrIO, daisySize: Int) {
  val counter = RegInit(UInt(0, log2Up(daisySize+1)))
  def isNotZero = counter.orR

  // Daisy chain control logic
  when(ctrlIo.copyCond) {
    counter := UInt(daisySize)
  }
  when(ctrlIo.readCond && ctrlIo.outFire && !ctrlIo.inValid) {
    counter := counter - UInt(1)
  }
}


// Define state daisy chains
class RegChainControlIO extends DaisyControlIO

class RegChainControl extends DaisyChainModule {
  val io = new RegChainControlIO
  val copied = RegNext(io.stall) 
  val counter = new DaisyCounter(io.ctrlIo, daisySize)
  
  io.ctrlIo.cntrNotZero := counter.isNotZero
  io.ctrlIo.copyCond := io.stall && !copied
  io.ctrlIo.readCond := io.stall && copied && counter.isNotZero
}

class RegChainIO extends Bundle {
  val stall = Bool(INPUT)
  val dataIo = new DataIO
}

class RegChain extends Module with DaisyChainParams {
  val io = new RegChainIO
  val datapath = Module(new DaisyDatapath)
  val control = Module(new RegChainControl)

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

class SRAMChainControl extends DaisyChainModule with SRAMChainParams {
  val io = new SRAMChainControlIO
  val s_IDLE :: s_ADDRGEN :: s_MEMREAD :: s_DONE :: Nil = Enum(UInt(), 4)
  val addrState = Reg(init=s_IDLE)
  val addrIn = Reg(UInt(width=log2Up(n)))
  val addrOut = Reg(UInt(width=log2Up(n)))
  val counter = new DaisyCounter(io.ctrlIo, daisySize)

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
      when(io.stall) {
        addrState := s_IDLE
      }
      io.addrIo.out.bits := addrIn
      io.addrIo.out.valid := io.stall
    }
  }
}

class SRAMChainIO extends RegChainIO {
  val restart = Bool(INPUT)
  val addrIo = new AddrIO
}

class SRAMChain extends Module with DaisyChainParams {
  val io = new SRAMChainIO
  val datapath = Module(new DaisyDatapath)
  val control = Module(new SRAMChainControl)

  io.stall <> control.io.stall
  io.restart <> control.io.restart
  io.dataIo <> datapath.io.dataIo
  io.addrIo <> control.io.addrIo
  control.io.ctrlIo <> datapath.io.ctrlIo
}
