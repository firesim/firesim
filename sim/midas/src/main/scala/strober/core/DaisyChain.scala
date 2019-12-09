// See LICENSE for license details.

package strober
package core

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.ParameterizedBundle

case object DaisyWidth extends Field[Int]
case object DataWidth extends Field[Int]
case object MemWidth extends Field[Int]
case object MemDepth extends Field[Int]
case object MemNum extends Field[Int]
case object SeqRead extends Field[Boolean]
case object SRAMChainNum extends Field[Int]

object ChainType extends Enumeration { val Trace, Regs, SRAM, RegFile, Cntr = Value }

// Declare daisy pins
class DaisyData(daisywidth: Int) extends Bundle {
  val in = Flipped(Decoupled(UInt(daisywidth.W)))
  val out = Decoupled(UInt(daisywidth.W))
  val load = Input(Bool())
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

class DaisyBox(implicit p: Parameters) extends Module {
  val io = IO(new DaisyBundle(p(DaisyWidth), p(SRAMChainNum)))
  io := DontCare
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
  val in   = Flipped(Decoupled(Input(UInt(daisyWidth.W))))
  val out  = Decoupled(Input(UInt(daisyWidth.W)))
  val data = Vec(daisyLen, Input(UInt(daisyWidth.W)))
  val load = Valid(Vec(daisyLen, UInt(daisyWidth.W)))
}

class CntrIO extends Bundle {
  val copyCond = Output(Bool())
  val readCond = Output(Bool())
  val loadCond = Output(Bool())
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
  io.dataIo.load.bits <> regs
  io.dataIo.load.valid := io.ctrlIo.loadCond
}

class DaisyControlIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val stall = Input(Bool())
  val load  = Input(Bool())
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
  io.ctrlIo.copyCond := io.stall && !copied || RegNext(reset.toBool)
  io.ctrlIo.readCond := io.stall && copied && counter.isNotZero
  io.ctrlIo.loadCond := io.stall && io.load
}

class RegChainIO(implicit p: Parameters) extends DaisyChainBundle()(p) {
  val stall = Input(Bool())
  val load  = Input(Bool())
  val dataIo = new DataIO
}

class RegChain(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = IO(new RegChainIO)
  val datapath = Module(new RegChainDatapath)
  val control = Module(new RegChainControl)

  control.io.stall := io.stall
  control.io.load  := io.load
  datapath.io.ctrlIo <> control.io.ctrlIo
  io.dataIo <> datapath.io.dataIo
}

// Define sram daisy chains
trait SRAMChainParameters {
  implicit val p: Parameters
  val seqRead = p(SeqRead)
  val n = p(MemNum)
  val w = log2Ceil(p(MemDepth)) max 1
}

class SRAMChainDatapath(implicit p: Parameters) extends RegChainDatapath()(p)

class AddrIO(implicit override val p: Parameters)
    extends ParameterizedBundle()(p) with SRAMChainParameters {
  val in = Flipped(Valid(UInt(w.W)))
  val out = Valid(UInt(w.W))
}

class SRAMChainControlIO(implicit override val p: Parameters)
    extends DaisyControlIO()(p) with SRAMChainParameters {
  val restart = Input(Bool())
  val addrIo = Vec(n, new AddrIO)
}

class SRAMChainControl(implicit override val p: Parameters)
    extends DaisyChainModule()(p) with SRAMChainParameters {
  val io = IO(new SRAMChainControlIO)
  val s_IDLE :: s_ADDRGEN :: s_MEMREAD :: s_DONE :: Nil = Enum(4)
  val addrState = RegInit(s_IDLE)
  val addrIns = Seq.fill(n)(Reg(UInt(w.W)))
  val addrOut = Reg(UInt(w.W))
  val counter = new DaisyCounter(io.stall, io.ctrlIo, daisyLen)
  val addrValid =
    if (seqRead) addrState === s_ADDRGEN || addrState === s_MEMREAD
    else         addrState === s_MEMREAD
  io.ctrlIo.cntrNotZero := counter.isNotZero
  io.ctrlIo.copyCond := addrState === s_MEMREAD
  io.ctrlIo.readCond := addrState === s_DONE && counter.isNotZero
  io.ctrlIo.loadCond := addrState === s_DONE && io.load
  (io.addrIo zip addrIns) foreach { case (addrIo, addrIn) =>
    addrIo.out.valid := addrValid
    if (seqRead) {
      addrIo.out.bits :=
        Mux(addrState === s_ADDRGEN, addrOut,
        Mux(io.ctrlIo.loadCond, addrOut - 1.U, addrIn))
      when(addrIo.in.valid) { addrIn := addrIo.in.bits }
    } else {
      addrIo.out.bits := Mux(io.ctrlIo.loadCond, addrOut - 1.U, addrOut)
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

class SRAMChainIO(implicit override val p: Parameters)
    extends RegChainIO()(p) with SRAMChainParameters {
  val restart = Input(Bool())
  val addrIo = Vec(n, new AddrIO)
}

class SRAMChain(implicit p: Parameters) extends DaisyChainModule()(p) {
  val io = IO(new SRAMChainIO)
  val datapath = Module(new SRAMChainDatapath)
  val control = Module(new SRAMChainControl)

  control.io.restart := io.restart
  control.io.stall := io.stall
  control.io.load  := io.load
  datapath.io.ctrlIo <> control.io.ctrlIo
  io.dataIo <> datapath.io.dataIo
  (io.addrIo zip control.io.addrIo) foreach { case (x, y) => x <> y }
}
