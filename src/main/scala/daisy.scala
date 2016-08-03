package strober

import Chisel._
import cde.{Parameters, Field}
import scala.collection.mutable.HashMap

case object DaisyWidth extends Field[Int]
case object DataWidth extends Field[Int]
case object SRAMSize extends Field[Int]
case object SRAMChainNum extends Field[Int]

object ChainType extends Enumeration { val Trace, Regs, SRAM, Cntr = Value }

// Declare daisy pins
class DaisyData(daisywidth: Int) extends Bundle {
  val in = Decoupled(UInt(width=daisywidth)).flip
  val out = Decoupled(UInt(width=daisywidth))
  override def cloneType: this.type =
    new DaisyData(daisywidth).asInstanceOf[this.type]
}

class RegData(daisywidth: Int) extends DaisyData(daisywidth) {
  override def cloneType: this.type =
    new RegData(daisywidth).asInstanceOf[this.type]
}
class TraceData(daisywidth: Int) extends DaisyData(daisywidth) {
  override def cloneType: this.type =
    new TraceData(daisywidth).asInstanceOf[this.type]
}
class SRAMData(daisywidth: Int) extends DaisyData(daisywidth) {
  val restart = Bool(INPUT)
  override def cloneType: this.type =
    new SRAMData(daisywidth).asInstanceOf[this.type]
}
class CntrData(daisywidth: Int) extends DaisyData(daisywidth) {
  override def cloneType: this.type =
    new CntrData(daisywidth).asInstanceOf[this.type]
}

class DaisyBundle(daisyWidth: Int, sramChainNum: Int) extends Bundle {
  val regs  = Vec(1, new RegData(daisyWidth))
  val trace = Vec(1, new TraceData(daisyWidth))
  val cntr  = Vec(1, new CntrData(daisyWidth))
  val sram  = Vec(sramChainNum, new SRAMData(daisyWidth))
  def apply(t: ChainType.Value) = t match {
    case ChainType.Regs  => regs
    case ChainType.Trace => trace
    case ChainType.SRAM  => sram
    case ChainType.Cntr  => cntr
  }
  override def cloneType: this.type =
    new DaisyBundle(daisyWidth, sramChainNum).asInstanceOf[this.type]
}

// Common structures for daisy chains
trait DaisyChainParams {
  implicit val p: Parameters
  val dataWidth = p(DataWidth)
  val daisyWidth = p(DaisyWidth)
  val daisyLen = (dataWidth-1)/daisyWidth + 1
}

abstract class DaisyChainBundle(implicit val p: Parameters) 
    extends junctions.ParameterizedBundle with DaisyChainParams

class DataIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val in  = Decoupled(UInt(INPUT, daisyWidth)).flip
  val out = Decoupled(UInt(INPUT, daisyWidth))
  val data = Vec(daisyLen, UInt(INPUT, daisyWidth))
}

class CntrIO extends Bundle {
  val copyCond = Bool(OUTPUT)
  val readCond = Bool(OUTPUT)
  val cntrNotZero = Bool(OUTPUT)
  val outFire = Bool(INPUT)
  val inValid = Bool(INPUT)
}

class DaisyDatapathIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val dataIo = new DataIO
  val ctrlIo = (new CntrIO).flip
}

abstract class DaisyChainModule(implicit val p: Parameters) extends Module with DaisyChainParams

class DaisyDatapath(implicit p: Parameters) extends DaisyChainModule()(p) { 
  val io = new DaisyDatapathIO
  val regs = Reg(Vec(daisyLen, UInt(width=daisyWidth)))

  io.dataIo.out.bits := regs(daisyLen-1)
  io.dataIo.out.valid := io.ctrlIo.cntrNotZero
  io.dataIo.in.ready := io.dataIo.out.ready
  io.ctrlIo.outFire := io.dataIo.out.fire()
  io.ctrlIo.inValid := io.dataIo.in.valid

  val readCondAndOutFire = io.ctrlIo.readCond && io.dataIo.out.fire()
  for (i <- (0 until daisyLen).reverse) {
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

class DaisyControlIO(implicit p: Parameters) extends junctions.ParameterizedBundle()(p) {
  val stall = Bool(INPUT)
  val ctrlIo = new CntrIO
}

class DaisyCounter(stall: Bool, ctrlIo: CntrIO, daisyLen: Int) {
  val counter = RegInit(UInt(0, log2Up(daisyLen+1)))
  def isNotZero = counter.orR
  counter suggestName "counter"

  // Daisy chain control logic
  when(!stall) {
    counter:= UInt(0)
  }.elsewhen(ctrlIo.copyCond) {
    counter := UInt(daisyLen)
  }.elsewhen(ctrlIo.readCond && ctrlIo.outFire && !ctrlIo.inValid) {
    counter := counter - UInt(1)
  }
}


// Define state daisy chains
class RegChainControlIO(implicit p: Parameters) extends DaisyControlIO()(p)

class RegChainControl(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = new RegChainControlIO
  val copied = RegNext(io.stall)
  val counter = new DaisyCounter(io.stall, io.ctrlIo, daisyLen)
  
  io.ctrlIo.cntrNotZero := counter.isNotZero
  io.ctrlIo.copyCond := io.stall && !copied || RegNext(reset)
  io.ctrlIo.readCond := io.stall && copied && counter.isNotZero
}

class RegChainIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val stall = Bool(INPUT)
  val dataIo = new DataIO
}

class RegChain(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = new RegChainIO
  val datapath = Module(new DaisyDatapath)
  val control = Module(new RegChainControl)

  control.io.stall := io.stall
  datapath.io.ctrlIo <> control.io.ctrlIo
  io.dataIo <> datapath.io.dataIo
}

// Define sram daisy chains
class AddrIO(implicit p: Parameters) extends junctions.ParameterizedBundle()(p) {
  val n = p(SRAMSize)
  val in = UInt(INPUT, width=log2Up(n)) // TODO: it turns out that this is wasteful, but required for tesing...
  val out = Valid(UInt(width=log2Up(n)))
}

class SRAMChainControlIO(implicit p: Parameters) extends DaisyControlIO()(p) {
  val restart = Bool(INPUT)
  val addrIo = new AddrIO
}

class SRAMChainControl(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = new SRAMChainControlIO
  val n = p(SRAMSize)
  val s_IDLE :: s_ADDRGEN :: s_MEMREAD :: s_DONE :: Nil = Enum(UInt(), 4)
  val addrState = RegInit(s_IDLE)
  val addrIn = Reg(UInt(width=log2Up(n))) 
  val addrOut = Reg(UInt(width=log2Up(n)))
  val counter = new DaisyCounter(io.stall, io.ctrlIo, daisyLen)

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

class SRAMChainIO(implicit p: Parameters) extends RegChainIO()(p) {
  val restart = Bool(INPUT)
  val addrIo = new AddrIO
}

class SRAMChain(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = new SRAMChainIO
  val datapath = Module(new DaisyDatapath)
  val control = Module(new SRAMChainControl)

  io.stall <> control.io.stall
  io.restart <> control.io.restart
  io.dataIo <> datapath.io.dataIo
  io.addrIo <> control.io.addrIo
  control.io.ctrlIo <> datapath.io.ctrlIo
}
