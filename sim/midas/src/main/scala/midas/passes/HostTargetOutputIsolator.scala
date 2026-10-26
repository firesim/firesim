// See LICENSE for license details.

package midas.passes

import firrtl.ir._
import firrtl.annotations._
import firrtl.transforms.BlackBoxInlineAnno
import firrtl.Utils.BoolType

/** Physical branch isolation; its logical function is exactly O = I. */
object HostTargetOutputIsolator {
  val name     = "HostTargetOutputIsolator"
  val blackbox =
    ExtModule(NoInfo, name, Seq(Port(NoInfo, "I", Input, BoolType), Port(NoInfo, "O", Output, BoolType)), name, Nil)
  def inline(circuit: String): BlackBoxInlineAnno = BlackBoxInlineAnno(
    ModuleName(name, CircuitName(circuit)),
    name + ".v",
    s"""|(* dont_touch = "yes" *) module $name(input I, output O);
        |`ifdef SYNTHESIS
        |  (* dont_touch = "yes" *) LUT1 #(.INIT(2'h2)) identity_lut (.I0(I), .O(O));
        |`else
        |  assign O = I;
        |`endif
        |endmodule
        |""".stripMargin,
  )
}
