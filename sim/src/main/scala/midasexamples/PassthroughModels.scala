
// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.widgets.FuzzingUIntSourceBridge
import midas.targetutils._

class ModuleNester[T <: Data](gen: () => PassthroughModule[T]) extends Module {
  val child = Module(gen())
  val io = IO(child.io.cloneType)
  io <> child.io
}

class ForkModule[T <: Data](gen: T, aStages: Int = 0, bStages: Int = 0) extends Module {
  val io = IO(new Bundle {
    val in = Input(gen)
    val outA = Output(gen)
    val outB = Output(gen)
  })
  io.outA := Seq.fill(aStages)(Reg(gen))
   .foldLeft(io.in) { case (in, stage) => stage := in; stage }
  io.outB := Seq.fill(bStages)(Reg(gen))
   .foldLeft(io.in) { case (in, stage) => stage := in; stage }
}

class PassthoughModuleIO[T <: Data](private val gen: T) extends Bundle {
  val in = Input(gen)
  val out = Output(gen)
}

class PassthroughModule[T <: Data](gen: T) extends Module {
  val io = IO(new PassthoughModuleIO(gen))
  io.out := io.in
  val dummy = RegNext(io.in)
  dontTouch(dummy)
}

class PassthroughModelDUT extends Module {
  val io = IO(new Bundle {})
  val lfsr = chisel3.util.random.LFSR(16)

  val passthru = Module(new PassthroughModule(UInt(16.W)))
  annotate(FAMEModelAnnotation(passthru))
  passthru.io.in := lfsr
  assert(passthru.io.in === passthru.io.out)
}
class PassthroughModel(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new PassthroughModelDUT)

class PassthroughModelNestedDUT extends Module {
  val io = IO(new Bundle {})
  val lfsr = chisel3.util.random.LFSR(16)

  val passthru = Module(new ModuleNester(() => new PassthroughModule(UInt(16.W))))
  annotate(FAMEModelAnnotation(passthru))
  annotate(FAMEModelAnnotation(passthru.child))
  passthru.io.in := lfsr
  assert(passthru.io.in === passthru.io.out)
}
class PassthroughModelNested(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new PassthroughModelNestedDUT)


class PassthroughModelBridgeSourceDUT extends Module {
  val io = IO(new Bundle {})
  val fuzz = Module(new FuzzingUIntSourceBridge(16))
  fuzz.io.clock := clock

  val passthru = Module(new PassthroughModule(UInt(16.W)))
  annotate(FAMEModelAnnotation(passthru))
  passthru.io.in := fuzz.io.uint
  assert(fuzz.io.uint === passthru.io.out)
}
class PassthroughModelBridgeSource(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new PassthroughModelBridgeSourceDUT)
