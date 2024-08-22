// See LICENSE for license details.

package goldengate.tests

import org.scalatest.flatspec.AnyFlatSpec

import chisel3._

import midas.targetutils._

class FpgaDebugSpec extends AnyFlatSpec with ElaborationUtils {
  def annotator(t: Bool) = FpgaDebug(t)
  def ioGen = Input(Bool())

  behavior.of("FPGADebug")
  checkBehaviorOnUnboundTargets(ioGen, annotator)
}
