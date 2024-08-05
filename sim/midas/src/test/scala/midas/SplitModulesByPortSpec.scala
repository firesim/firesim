package goldengate.tests

import firrtl._

import chisel3._
import chisel3.util._

import org.scalatest.freespec.AnyFreeSpec

class X extends Bundle {
  val a = Flipped(Decoupled(UInt(2.W)))
  val b = Decoupled(UInt(2.W))
}

class SimpleSubModule extends Module {
  val ENTRIES = 4
  val io      = IO(new Bundle {
    val x = new X
  })
  dontTouch(io)

  val x_w = Wire(new X)
  x_w.a  <> io.x.a
  io.x.b <> x_w.b

  val que = Module(new Queue(UInt(2.W), 2))
  que.io.enq <> x_w.a

  val waddr = Wire(UInt(2.W))
  waddr := ~(que.io.deq.bits)

  val data = Wire(UInt(2.W))
  data := que.io.deq.bits

  val mem = Mem(ENTRIES, UInt(2.W))
  when(que.io.deq.valid) {
    mem.write(data, waddr)
  }
  que.io.deq.ready := true.B

  val raddr = Wire(UInt(2.W))
  x_w.b.valid := RegNext(que.io.deq.valid)
  raddr       := RegNext(waddr)

  when(x_w.b.fire) {
    x_w.b.bits := mem.read(raddr)
  }.otherwise {
    x_w.b.bits := 0.U
  }
}

class SimpleSubModuleWrapper(CNT: Int) extends Module {
  val io      = IO(new Bundle {
    val x = Vec(CNT, new X)
  })
  val submods = Seq.fill(CNT)(Module(new SimpleSubModule))
  for (i <- 0 until CNT) {
    submods(i).io.x.a <> io.x(i).a
    io.x(i).b         <> submods(i).io.x.b
  }
}

class SimpleWidget(CNT: Int) extends Module {
  val io = IO(new Bundle {
    val x_in  = Vec(CNT, new X)
    val x_out = Vec(CNT, Flipped(new X))
  })
  dontTouch(io)

  for (i <- 0 until CNT) {
    io.x_out(i).a <> io.x_in(i).a
    io.x_in(i).b  <> io.x_out(i).b
  }
}

class SimpleModule extends Module {
  val CNT = 2

  val io = IO(new Bundle {
    val x = Vec(CNT, new X)
  })
  dontTouch(io)

  val submods = Module(new SimpleSubModuleWrapper(CNT))
  val widget  = Module(new SimpleWidget(CNT))
  for (i <- 0 until CNT) {
    widget.io.x_in(i).a <> io.x(i).a
    submods.io.x(i).a   <> widget.io.x_out(i).a

    widget.io.x_out(i).b <> submods.io.x(i).b
    io.x(i).b            <> widget.io.x_in(i).b
  }
}

class FirrtlGenerator extends AnyFreeSpec with GoldenGateCompilerTest {
  def generateFirrtl() = {
    val (firrtl, _) = compile(new SimpleModule, "low", a = Seq())

    writeFile("midas/test-inputs", "simple.fir", firrtl)
  }
}

class GenerateFirrtlForSplitModuleTest extends FirrtlGenerator {
  generateFirrtl()
}
