package strober
package core

import util.ParameterizedBundle // from rocketchip
import chisel3._
import chisel3.util._
import config.{Parameters, Field}

case object DaisyWidth extends Field[Int]
case object DataWidth extends Field[Int]
case object MemWidth extends Field[Int]
case object MemDepth extends Field[Int]
case object MemNum extends Field[Int]
case object SRAMChainNum extends Field[Int]

object ChainType extends Enumeration { val Trace, Regs, SRAM, RegFile, Cntr = Value }

// Declare daisy pins
class DaisyData(daisywidth: Int) extends Bundle {
  val in = Flipped(Decoupled(UInt(daisywidth.W)))
  val out = Decoupled(UInt(daisywidth.W))
  override def cloneType: this.type =
    new DaisyData(daisywidth).asInstanceOf[this.type]
}

class RegData(daisywidth: Int) extends DaisyData(daisywidth) {
  override def cloneType: this.type =
    new RegData(daisywidth).asInstanceOf[this.type]
}

class SRAMData(daisywidth: Int) extends DaisyData(daisywidth) {
  val restart = Input(Bool())
  override def cloneType: this.type =
    new SRAMData(daisywidth).asInstanceOf[this.type]
}

class DaisyBundle(val daisyWidth: Int, sramChainNum: Int) extends Bundle {
  val trace   = Vec(1, new RegData(daisyWidth))
  val regs    = Vec(1, new RegData(daisyWidth))
  val sram    = Vec(sramChainNum, new SRAMData(daisyWidth))
  val regfile = Vec(1, new SRAMData(daisyWidth))
  val cntr    = Vec(1, new RegData(daisyWidth))
  def apply(t: ChainType.Value) = t match {
    case ChainType.Regs    => regs
    case ChainType.Trace   => trace
    case ChainType.SRAM    => sram
    case ChainType.RegFile => regfile
    case ChainType.Cntr    => cntr
  }
  override def cloneType: this.type =
    new DaisyBundle(daisyWidth, sramChainNum).asInstanceOf[this.type]
}

class DaisyBox(implicit p: Parameters) extends BlackBox {
  val io = IO(new Bundle {
    val daisy = new DaisyBundle(p(DaisyWidth), p(SRAMChainNum))
  })
}

// Common structures for daisy chains
trait DaisyChainParams {
  implicit val p: Parameters
  val dataWidth = p(DataWidth)
  val daisyWidth = p(DaisyWidth)
  val daisyLen = (dataWidth-1)/daisyWidth + 1
}

abstract class DaisyChainBundle(implicit val p: Parameters) 
    extends ParameterizedBundle with DaisyChainParams

class DataIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val in = Flipped(Decoupled(Input(UInt(daisyWidth.W))))
  val out = Decoupled(Input(UInt(daisyWidth.W)))
  val data = Vec(daisyLen, Input(UInt(daisyWidth.W)))
}

class CntrIO extends Bundle {
  val copyCond = Output(Bool())
  val readCond = Output(Bool())
  val cntrNotZero = Output(Bool())
  val outFire = Input(Bool())
  val inValid = Input(Bool())
}

class DaisyDatapathIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val dataIo = new DataIO
  val ctrlIo = Flipped(new CntrIO)
}

abstract class DaisyChainModule(implicit val p: Parameters) extends Module with DaisyChainParams

class RegChainDatapath(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = IO(new DaisyDatapathIO)
  val regs = Reg(Vec(daisyLen, UInt(daisyWidth.W)))

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

class DaisyControlIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val stall = Input(Bool())
  val ctrlIo = new CntrIO
}

class DaisyCounter(stall: Bool, ctrlIo: CntrIO, daisyLen: Int) {
  val counter = RegInit(0.U(log2Up(daisyLen+1).W))
  def isNotZero = counter.orR
  counter suggestName "counter"

  // Daisy chain control logic
  when(!stall) {
    counter:= 0.U
  }.elsewhen(ctrlIo.copyCond) {
    counter := daisyLen.U
  }.elsewhen(ctrlIo.readCond && ctrlIo.outFire && !ctrlIo.inValid) {
    counter := counter - 1.U
  }
}


// Define state daisy chains
class RegChainControlIO(implicit p: Parameters) extends DaisyControlIO()(p)

class RegChainControl(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = IO(new RegChainControlIO)
  val copied = RegNext(io.stall)
  val counter = new DaisyCounter(io.stall, io.ctrlIo, daisyLen)
  
  io.ctrlIo.cntrNotZero := counter.isNotZero
  io.ctrlIo.copyCond := io.stall && !copied || RegNext(reset)
  io.ctrlIo.readCond := io.stall && copied && counter.isNotZero
}

class RegChainIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val stall = Input(Bool())
  val dataIo = new DataIO
}

class RegChain(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = IO(new RegChainIO)
  val datapath = Module(new RegChainDatapath)
  val control = Module(new RegChainControl)

  control.io.stall := io.stall
  datapath.io.ctrlIo <> control.io.ctrlIo
  io.dataIo <> datapath.io.dataIo
}

// Define sram daisy chains
class SRAMChainDatapath(implicit p: Parameters) extends RegChainDatapath()(p)

class AddrIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val n = p(MemDepth)
  val out = Valid(UInt(log2Up(n).W))
}

class ReadIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val in = Flipped(Valid(UInt(p(MemWidth).W)))
  val out = Valid(UInt(p(MemWidth).W))
}

class SRAMChainControlIO(implicit p: Parameters) extends DaisyControlIO()(p) {
  val restart = Input(Bool())
  val addrIo = new AddrIO
  val readIo = Vec(p(MemNum), new ReadIO)
}

class SRAMChainControl(implicit p: Parameters) extends DaisyChainModule()(p) {
  val n = p(MemDepth)
  val io = IO(new SRAMChainControlIO)
  val s_IDLE :: s_ADDRGEN :: s_MEMREAD :: s_DONE :: Nil = Enum(UInt(), 4)
  val addrState = RegInit(s_IDLE)
  val addrOut = Reg(UInt(log2Up(n).W))
  val counter = new DaisyCounter(io.stall, io.ctrlIo, daisyLen)

  io.ctrlIo.cntrNotZero := counter.isNotZero
  io.ctrlIo.copyCond := addrState === s_MEMREAD 
  io.ctrlIo.readCond := addrState === s_DONE && counter.isNotZero
  io.addrIo.out.bits := addrOut
  io.addrIo.out.valid := addrState === (if (p(MemNum) > 0) s_ADDRGEN else s_MEMREAD)
  io.readIo.zipWithIndex foreach { case (readIo, i) =>
    // Read port output values are destoyed with SRAM snapshotting
    // Thus, capture their values here
    val read = Reg(readIo.out.cloneType)
    val readEnable = Reg(Bool())
    read suggestName s"read_${i}"
    readEnable suggestName s"readEnable_${i}"
    readIo.out := read
    // With sram reads, turn off io.read.out and keep their values
    when(reset || readIo.in.valid) {
      read.valid := Bool(false)
      readEnable := Bool(true)
    }
    when(RegNext(reset || readIo.in.valid)) {
      read.bits := readIo.in.bits
    }
    // Turn on io.read.out only when there's snapshotting
    when(io.stall && io.restart) {
      read.valid := readEnable
    }
  }

  // SRAM control
  switch(addrState) {
    is(s_IDLE) {
      addrState := Mux(io.stall, s_DONE, s_IDLE)
      addrOut   := 0.U
    }
    is(s_ADDRGEN) {
      addrState := s_MEMREAD
    }
    is(s_MEMREAD) {
      addrState := s_DONE
      addrOut   := addrOut + 1.U
    }
    is(s_DONE) {
      addrState := Mux(io.restart, s_ADDRGEN,
                   Mux(io.stall, s_DONE, s_IDLE))
    }
  }
}

class SRAMChainIO(implicit p: Parameters) extends RegChainIO()(p) {
  val restart = Input(Bool())
  val addrIo = new AddrIO
  val readIo = Vec(p(MemNum), new ReadIO)
}

class SRAMChain(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = IO(new SRAMChainIO)
  val datapath = Module(new SRAMChainDatapath)
  val control = Module(new SRAMChainControl)

  control.io.restart := io.restart
  control.io.stall := io.stall
  datapath.io.ctrlIo <> control.io.ctrlIo
  io.dataIo <> datapath.io.dataIo
  io.addrIo <> control.io.addrIo
  (io.readIo zip control.io.readIo) foreach { case (x, y) => x <> y }
}
