//See LICENSE for license details.

package firesim.midasexamples

import freechips.rocketchip.config.Parameters
import midas.targetutils.xdc._

class CustomConstraintsDUT extends ShiftRegisterDUT {
  XDC(XDCFiles.Synthesis, "constrain_synth1")
  XDC(XDCFiles.Synthesis, "constrain_synth2 [reg {}]", r0)
  XDC(XDCFiles.Implementation, "constrain_impl1")
  XDC(XDCFiles.Implementation, "constrain_impl2 [reg {}]", r1)
}

class CustomConstraints(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new CustomConstraintsDUT)
