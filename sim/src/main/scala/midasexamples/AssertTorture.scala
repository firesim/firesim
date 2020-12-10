//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.util.ResetCatchAndSync
import freechips.rocketchip.config.{Parameters, Field}
import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}

import scala.util.Random

case object AssertionsToGenerate extends Field[Int](4096)
case object AssertionClockCount extends Field[Int](4)
case object NewModuleProbability extends Field[Double](0.1)
case object RNGKey extends Field[Random](new Random(1023))

trait AssertTortureConstants {
  val printfPrefix = "PRINTF_VALIDATION: "
  def clockPrefix(i: Int): String = s"C${i}, "
}

/**
  * A module that recursively instantiates itself, generating random assertions
  * synchronous to one of the provided clocks. All generated assertions will fire in
  * the order they are instantiated, providing a simple check that they are
  * correctly observed by the bridge.
  *
  * @param initialIndexes A count of the number of assertions generated in each
  * clock domain so far
  *
  * @param moduleDepth Specifies the current depth in the submodule hierarchy.
  * Used to bound recursion depth and to determine whether it is safe to
  * return to the enclosing module.
  */

class AssertTortureModule(
    initialIndexes: Seq[Int],
    moduleDepth: Int = 0)(implicit p: Parameters) extends RawModule with AssertTortureConstants {
  val maxModuleDepth = 32
  val numClocks = initialIndexes.size
  val clocks = IO(Input(Vec(numClocks, Clock())))
  val resets = IO(Input(Vec(numClocks, Bool())))

  // Count the number of cycles in each clock domain so orthogonal assertions can be generated.
  val counters = for ((clock, reset) <- (clocks.zip(resets))) yield {
    val countW = Wire(UInt(32.W))
    withClockAndReset(clock, reset) {
      val count = RegInit(0.U(32.W))
      count := count + 1.U
      countW := count
    }
    countW
  }

  def createAssertion(indexes: Seq[Int]): Seq[Int] = {
    val clockIdx = p(RNGKey).nextInt(numClocks)
    val assertIdx = indexes(clockIdx)
    withClockAndReset(clocks(clockIdx), resets(clockIdx)) {
      val message = s"${clockPrefix(clockIdx)}A${assertIdx}"
      val condition = counters(clockIdx) =/= assertIdx.U
      when(!condition) { printf(s"${printfPrefix}${message}\n") }
      assert(condition, message)
    }
    indexes.updated(clockIdx, assertIdx + 1)
  }

  def returnToParent(): Boolean = moduleDepth > 0 && p(RNGKey).nextDouble < p(NewModuleProbability)
  def createChild(): Boolean = p(RNGKey).nextDouble < p(NewModuleProbability) && moduleDepth < maxModuleDepth
  def recurse(indexes: Seq[Int]): Seq[Int] = indexes match {
    case idxs if idxs.reduce(_+_) == p(AssertionsToGenerate) || returnToParent => idxs
    case idxs if createChild() =>
      val child = Module(new AssertTortureModule(idxs, moduleDepth + 1))
      child.clocks := clocks
      child.resets := resets
      recurse(child.assertIndexes)
    case idxs => recurse(createAssertion(idxs))
  }

  // The number of assertions generated as of Module close.
  val assertIndexes = recurse(initialIndexes)
}

class AssertTorture(implicit p: Parameters) extends RawModule {
  val clockInfo = Seq.tabulate(p(AssertionClockCount))(i => RationalClock(s"By${i+1}", 1, i+1))
  // Drop the first clock because the RCB generates it implicitly
  val clockBridge = RationalClockBridge((clockInfo.tail):_*)
  val baseClock = clockBridge.io.clocks.head
  val reset = WireInit(false.B)
  withClockAndReset(baseClock, reset) {
    val peekPokeBridge = PeekPokeBridge(baseClock, reset)
  }
  val resets = clockBridge.io.clocks.map(clock => ResetCatchAndSync(clock, reset.asBool))

  // Add an extra, undriven clock/reset pair to check that assertions that use
  // them they are rejected. While FIRRTL can and will optimize these cases
  // away usually there are instances where a users may dontTouch a signal that
  // preserves a dead assertion
  val undrivenClock = WireDefault(true.B.asClock)
  val undrivenReset = WireDefault(true.B)
  dontTouch(undrivenClock)
  dontTouch(undrivenReset)
  val dut = Module(new AssertTortureModule(Seq.fill(p(AssertionClockCount) + 1)(0)))
  dut.clocks := clockBridge.io.clocks :+ undrivenClock
  dut.resets := VecInit(resets :+ undrivenReset)
}
