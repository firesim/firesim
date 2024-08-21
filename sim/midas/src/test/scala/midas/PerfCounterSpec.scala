// See LICENSE for license details.

package goldengate.tests

import org.scalatest.flatspec.AnyFlatSpec

import chisel3._

import midas.targetutils._

class PerfCounterSpec extends AnyFlatSpec with ElaborationUtils {
  def annotator(t: Bool) = PerfCounter(t, "", "")
  def ioGen = Input(Bool())

  behavior.of("PerfCounter")
  checkBehaviorOnUnboundTargets(ioGen, annotator)
}
