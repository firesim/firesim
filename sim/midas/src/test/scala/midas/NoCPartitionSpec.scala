package goldengate.tests

import chisel3._
import chisel3.util._
import chisel3.experimental.IO

import firesim.lib.bridgeutils.RationalClock
import firesim.lib.bridges.{PeekPokeBridge, RationalClockBridge, ResetPulseBridge, ResetPulseBridgeParameters}

import freechips.rocketchip.prci._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters

class TLBundle extends Bundle {
  val a = Decoupled(UInt(4.W))
  val d = Flipped(Decoupled(UInt(4.W)))
}

class Tile extends Module {
  val io = IO(new Bundle {
    val tl     = new TLBundle
    val int    = Input(UInt(1.W))
    val hartid = Input(UInt(2.W))
  })
  dontTouch(io)

  val aValid = RegInit(false.B)
  aValid := !aValid

  io.tl.a.bits  := RegNext(io.tl.d.bits)
  io.tl.a.valid := aValid
  io.tl.d.ready := true.B
}

class Converter extends Module {
  val io = IO(new Bundle {
    val protocol = Flipped(new TLBundle)
    val nocin0   = Decoupled(UInt(4.W))
    val nocout0  = Flipped(Decoupled(UInt(4.W)))
  })

  io.nocin0     <> io.protocol.a
  io.protocol.d <> io.nocout0
}

class RouterDomain(iCnt: Int, oCnt: Int) extends Module {
  val io = IO(new Bundle {
    val in      = Vec(iCnt, Flipped(Decoupled(UInt(4.W))))
    val out     = Vec(oCnt, Decoupled(UInt(4.W)))
    val src_out = Decoupled(UInt(4.W))
    val dst_in  = Flipped(Decoupled(UInt(4.W)))
  })

  dontTouch(io)

  val readyReg = RegInit(false.B)
  readyReg := !readyReg

  for (i <- 0 until iCnt) {
    io.in(i).ready := readyReg
  }
  for (i <- 0 until oCnt) {
    io.out(i).valid := true.B
    io.out(i).bits  := 0.U
  }

  io.src_out.valid := true.B
  io.src_out.bits  := 0.U
  io.dst_in.ready  := true.B
}

class NoC extends Module {
  val io = IO(new Bundle {
    val in  = Vec(4, Flipped(Decoupled(UInt(4.W))))
    val out = Vec(5, Decoupled(UInt(4.W)))
  })

  val router_sink_domain   = Module(new RouterDomain(1, 1))
  val router_sink_domain_1 = Module(new RouterDomain(1, 1))
  val router_sink_domain_2 = Module(new RouterDomain(1, 1))
  val router_sink_domain_3 = Module(new RouterDomain(1, 1))
  val router_sink_domain_4 = Module(new RouterDomain(0, 1))

  router_sink_domain.io.in(0)   <> io.in(0)
  router_sink_domain_1.io.in(0) <> io.in(1)
  router_sink_domain_2.io.in(0) <> io.in(2)
  router_sink_domain_3.io.in(0) <> io.in(3)

  io.out(0) <> router_sink_domain.io.out(0)
  io.out(1) <> router_sink_domain_1.io.out(0)
  io.out(2) <> router_sink_domain_2.io.out(0)
  io.out(3) <> router_sink_domain_3.io.out(0)
  io.out(4) <> router_sink_domain_4.io.out(0)

  router_sink_domain_1.io.dst_in <> router_sink_domain.io.src_out
  router_sink_domain_2.io.dst_in <> router_sink_domain_1.io.src_out
  router_sink_domain_3.io.dst_in <> router_sink_domain_2.io.src_out
  router_sink_domain_4.io.dst_in <> router_sink_domain_3.io.src_out
  router_sink_domain.io.dst_in   <> router_sink_domain_4.io.src_out
}

class ProtocolNoC extends Module {
  val io = IO(new Bundle {
    val ingress = Vec(4, Flipped(new TLBundle))
    val egress  = Decoupled(UInt(4.W))
  })

  val converters = Seq.fill(4)(Module(new Converter))
  val noc        = Module(new NoC())
  for (i <- 0 until 4) {
    converters(i).io.protocol <> io.ingress(i)
    noc.io.in(i)              <> converters(i).io.nocin0
    converters(i).io.nocout0  <> noc.io.out(i)
  }
  io.egress <> noc.io.out(4)
}

class TLFIFOFixer(n: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Vec(n, Flipped(new TLBundle))
    val out = Vec(n, new TLBundle)
  })
  for (i <- 0 until n) {
    val cur_in  = io.in(i)
    val cur_out = io.out(i)

    val stall  = (cur_in.a.bits === 0.U)
    val cntr   = RegInit(0.U(4.W))
    val cntr_1 = RegInit(0.U(4.W))

    cntr_1 := cntr
    cntr   := Mux(cur_in.a.fire, cntr + 1.U, cntr)

    dontTouch(cntr)
    dontTouch(cntr_1)

    cur_out.a       <> cur_in.a
    cur_out.a.valid := cur_in.a.valid && !stall && (cntr > 0.U)

    cur_in.d <> cur_out.d
  }
}

class RingNoC extends Module {
  val io   = IO(new Bundle {
    val ingress = Vec(4, Flipped(new TLBundle))
    val egress  = Decoupled(UInt(4.W))
  })
  val pnoc = Module(new ProtocolNoC)
  for (i <- 0 until 4) {
    pnoc.io.ingress(i) <> io.ingress(i)
  }
  io.egress <> pnoc.io.egress
}

class InterruptNode extends Module {
  val io = IO(new Bundle {
    val int = Output(UInt(1.W))
  })
  dontTouch(io)
  io.int := 0.U
}

class DigitalTop(implicit p: Parameters) extends LazyModule {
  override lazy val module = Module(new DigitalTopImp()(this))
}

class DigitalTopImp()(outer: DigitalTop) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val tlserdes = Decoupled(UInt(4.W))
  })

  val tile0 = Module(new Tile())
  val tile1 = Module(new Tile())
  val tile2 = Module(new Tile())
  val tile3 = Module(new Tile())

  val sbus  = Module(new RingNoC())
  val fixer = Module(new TLFIFOFixer(4))

  val intsources = Seq.fill(4)(Module(new InterruptNode()))

  tile0.io.int := intsources(0).io.int
  tile1.io.int := intsources(1).io.int
  tile2.io.int := intsources(2).io.int
  tile3.io.int := intsources(3).io.int

  tile0.io.hartid := 0.U(2.W)
  tile1.io.hartid := 1.U(2.W)
  tile2.io.hartid := 2.U(2.W)
  tile3.io.hartid := 3.U(2.W)

  fixer.io.in(0) <> tile0.io.tl
  fixer.io.in(1) <> tile1.io.tl
  fixer.io.in(2) <> tile2.io.tl
  fixer.io.in(3) <> tile3.io.tl

  for (i <- 0 until 4) {
    sbus.io.ingress(i) <> fixer.io.out(i)
  }

  io.tlserdes <> sbus.io.egress
}

class ChipTop(implicit p: Parameters) extends LazyModule {
  lazy val lazySystem                = LazyModule(new DigitalTop).suggestName("System")
  lazy val module: LazyModuleImpLike = new LazyRawModuleImp(this) {}
  val implicitClockSourceNode        = ClockSourceNode(Seq(ClockSourceParameters(name = Some("top_clock"))))
  val implicitClockSinkNode          = ClockSinkNode(Seq(ClockSinkParameters(name = Some("implicit_clock"))))
  implicitClockSinkNode := implicitClockSourceNode

  val topIO = InModuleBody {
    lazySystem.asInstanceOf[DigitalTop].module match {
      case l: LazyModuleImp => {
        val implicitClock = implicitClockSinkNode.in.head._1.clock
        val implicitReset = implicitClockSinkNode.in.head._1.reset
        l.clock := implicitClock
        l.reset := implicitReset
        val tio = IO(new Bundle {
          val htif = Decoupled(UInt(4.W))
        })
        tio.htif <> l.io.tlserdes
        tio
      }
    }
  }

  val clockIO = InModuleBody {
    implicitClockSourceNode.makeIOs()
  }
}

class FireSim(implicit p: Parameters) extends RawModule {
  freechips.rocketchip.util.property.cover.setPropLib(
    new midas.passes.FireSimPropertyLibrary()
  )

  val buildtopClock = Wire(Clock())
  val buildtopReset = WireInit(false.B)

  val dummy          = WireInit(false.B)
  val peekPokeBridge = PeekPokeBridge(buildtopClock, dummy)

  val resetBridge = Module(new ResetPulseBridge(ResetPulseBridgeParameters()))
  resetBridge.io.clock := buildtopClock
  buildtopReset        := resetBridge.io.reset

  midas.targetutils.GlobalResetCondition(buildtopReset)

  val lazyChipTop = LazyModule(new ChipTop())
  val chiptop     = Module(lazyChipTop.module)
  lazyChipTop match {
    case dut: ChipTop =>
      dut.clockIO.head.clock := buildtopClock
      dut.clockIO.head.reset := buildtopReset
      dut.topIO.htif.ready   := true.B
  }

  def dutReset = { require(false, "dutReset should not be used in FireSim"); false.B }
  def success = { require(false, "success should not be used in FireSim"); false.B }

  val allClocks   = Seq(RationalClock("baseClock", 1, 1))
  val clockBridge = Module(new RationalClockBridge(allClocks))
  buildtopClock := clockBridge.io.clocks(0)
}
