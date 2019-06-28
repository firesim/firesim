// See LICENSE for license details.

package strober
package replay

import mdf.macrolib._
import mdf.macrolib.Utils.readMDFFromString
import barstools.macros.Utils.filterForSRAM
import java.io.{File, FileWriter, Writer}

object StroberMacroEmitter {
  def apply(writer: Writer)(sram: SRAMMacro) {
    val tab = " "
    val addrWidth = chisel3.util.log2Ceil(sram.depth) max 1
    val portdefs = sram.ports flatMap (port => Seq(
      Seq(tab, "input", port.clock.get.name),
      Seq(tab, s"input[${addrWidth-1}:0]", port.address.name)) ++
      (port.writeEnable map (p => Seq(tab, "input", p.name))) ++
      (port.readEnable map (p => Seq(tab, "input", p.name))) ++
      (port.chipEnable map (p => Seq(tab, "input", p.name))) ++
      (port.output map (p => Seq(tab, s"output[${port.width.get-1}:0]", p.name))) ++
      (port.input map (p => Seq(tab, s"input[${port.width.get-1}:0]", p.name))) ++
      (port.maskPort map (p => Seq(tab, s"input[${sram.width/port.maskGran.get-1}:0]", p.name)))
    ) // TODO: extra ports...

    val declares = Seq(
          Seq(tab, s"reg[${sram.width-1}:0] ram[0:${sram.depth-1}];")) ++
      (sram.ports flatMap (port => port.output map (p =>
          Seq(tab, s"reg[${sram.width-1}:0]", s"${p.name}_reg;")))) ++
      Seq(Seq("`ifdef RANDOMIZE_MEM_INIT"),
          Seq(tab, "integer initvar;"),
          Seq(tab, "initial begin"),
          Seq(tab, tab, "#0.002;"),
          Seq(tab, tab, s"for (initvar = 0; initvar < ${sram.depth}; initvar = initvar + 1)"),
          Seq(tab, tab, tab, """/* verilator lint_off WIDTH */ ram[initvar] = {%d{$random}};""".format((sram.width - 1) / 32 + 1))) ++
      (sram.ports flatMap (port => port.output map (p =>
          Seq(tab, tab, "/* verilator lint_off WIDTH */ %s_reg = {%d{$random}};".format(p.name, (sram.width - 1) / 32 + 1))))) ++
      Seq(Seq(tab, "end"),
          Seq("`endif"))

    val assigns = sram.ports flatMap (port => port.output map (p =>
          Seq(tab, "assign", s"${p.name} = ${p.name}_reg;")))

    def inv(name: String, polarity: PortPolarity) = polarity match {
      case ActiveHigh | PositiveEdge => s"(${name})"
      case ActiveLow  | NegativeEdge => s"(~ ${name})"
    }

    val always = (sram.ports flatMap (port => port.output map { p =>
      val enable = (port.chipEnable, port.readEnable) match {
        case (Some(ce), Some(re)) =>
          "%s && %s".format(inv(ce.name, ce.polarity), inv(re.name, re.polarity))
        case (Some(ce), None) => inv(ce.name, ce.polarity)
        case (None, Some(re)) => inv(re.name, re.polarity)
        case (None, None) => "1'b1"
      }
      port.clock.get.name -> Seq(Seq(tab, tab, s"if (${enable})", s"${p.name}_reg <= ram[${port.address.name}];"))
    })) ++ (sram.ports flatMap (port => port.input map { p =>
      val enable = (port.chipEnable, port.writeEnable) match {
        case (Some(ce), Some(we)) =>
          "%s && %s".format(inv(ce.name, ce.polarity), inv(we.name, we.polarity))
        case (Some(ce), None) => inv(ce.name, ce.polarity)
        case (None, Some(we)) => inv(we.name, we.polarity)
        case (None, None) => "1'b1"
      }
      port.clock.get.name -> (((port.maskPort, port.maskGran): @unchecked) match {
        case (None, None) => Seq(
          Seq(tab, tab, s"if (${enable})", s"ram[${port.address.name}] <= ${p.name};"))
        case (Some(maskPort), Some(maskGran)) => (0 until (sram.width / maskGran)) map { k =>
          val range = s"${(k + 1) * maskGran - 1} : ${k * maskGran}"
          val mask = inv(s"${maskPort.name}[$k]", maskPort.polarity)
          Seq(tab, tab, s"if (${enable} && ${mask})", s"ram[${port.address.name}][$range] <= ${p.name}[$range];")
        }
      })
    }))

    writer write s"""
module ${sram.name}(
%s
);
%s
%s
endmodule""".format(
     portdefs map (_ mkString " ") mkString ",\n",
     (declares ++ assigns) map (_ mkString " ") mkString "\n",
     always map { case (clk, body) => s"""
  always @(posedge $clk) begin
%s
  end""".format(body map (_ mkString " ") mkString "\n")
     } mkString "\n"
    )
  }
}

class StroberVerilogEmitter(jsonFile: File, macroFile: File, pathFile: File) extends firrtl.VerilogEmitter {
  override def emit(state: firrtl.CircuitState, writer: Writer) {
    super.emit(state, writer)

    val str = scala.io.Source.fromFile(jsonFile).mkString
    val srams = readMDFFromString(str) map (_ collect { case x: SRAMMacro => x })
    val macroWriter = new FileWriter(macroFile)
    srams map (_ foreach StroberMacroEmitter(macroWriter))
    macroWriter.close

    val pathWriter = new FileWriter(pathFile)
    val insts = new firrtl.analyses.InstanceGraph(state.circuit)
    val paths = srams map (_ foreach { m =>
      (insts findInstancesInHierarchy m.name) foreach { is =>
        val path = is map (_.name) mkString "."
        pathWriter write s"${m.name} $path\n"
      }
    })
    pathWriter.close
  }
}
