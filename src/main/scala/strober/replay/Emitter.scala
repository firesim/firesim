package strober
package replay

import midas.passes.{MemConf, MemConfReader}
import java.io.{File, FileWriter, Writer}

object StroberMacroEmitter {
  def apply(writer: Writer)(conf: MemConf) {
    val tab = " "
    def maskWidth = (conf.width / conf.maskGran).toInt
    val addrWidth = chisel3.util.log2Up(conf.depth) max 1
    val portdefs = (conf.readers.indices flatMap (i => Seq(
      Seq(tab, "input", s"R${i}_clk"),
      Seq(tab, s"input[${addrWidth-1}:0]", s"R${i}_addr"),
      Seq(tab, "input", s"R${i}_en"),
      Seq(tab, s"output[${conf.width-1}:0]", s"R${i}_data"))
    )) ++ (conf.writers.zipWithIndex flatMap { case (w, i) => Seq(
      Seq(tab, "input", s"W${i}_clk"),
      Seq(tab, s"input[${addrWidth-1}:0]", s"W${i}_addr"),
      Seq(tab, "input", s"W${i}_en"),
      Seq(tab, s"input[${conf.width-1}:0]", s"W${i}_data")) ++
      (if (w.head == 'm') Seq(Seq(tab, s"input[${maskWidth-1}:0]", s"W${i}_mask")) else Nil)
    }) ++ (conf.readwriters.zipWithIndex flatMap { case (rw, i) => Seq(
      Seq(tab, "input", s"RW${i}_clk"),
      Seq(tab, s"input[${addrWidth-1}:0]", s"RW${i}_addr"),
      Seq(tab, "input", s"RW${i}_en"),
      Seq(tab, "input", s"RW${i}_wmode"),
      Seq(tab, s"input[${conf.width-1}:0]", s"RW${i}_wdata"),
      Seq(tab, s"output[${conf.width-1}:0]", s"RW${i}_rdata")) ++
      (if (rw.head == 'm') Seq(Seq(tab, s"input[${maskWidth-1}:0]", s"RW${i}_wmask")) else Nil)
    })
    val declares = Seq(Seq(tab, s"reg[${conf.width-1}:0] ram[0:${conf.depth-1}];")) ++
      (conf.readers.indices map (i => Seq(tab, s"reg[${conf.width-1}:0]", s"reg_R${i};"))) ++
      (conf.readwriters.indices map (i => Seq(tab, s"reg[${conf.width-1}:0]", s"reg_RW${i};"))) ++
      Seq(Seq("`ifdef RANDOMIZE_MEM_INIT"),
          Seq(tab, "integer initvar;"),
          Seq(tab, "initial begin"),
          Seq(tab, tab, "#0.002;"),
          Seq(tab, tab, s"for (initvar = 0; initvar < ${conf.depth}; initvar = initvar + 1)"),
          Seq(tab, tab, tab, """/* verilator lint_off WIDTH */ ram[initvar] = {%d{$random}};""".format((conf.width - 1) / 32 + 1))) ++
      (conf.readers.indices map (i =>
          Seq(tab, tab, "/* verilator lint_off WIDTH */ reg_R%d = {%d{$random}};".format(i, (addrWidth - 1) / 32 + 1)))) ++
      (conf.readwriters.indices map (i =>
          Seq(tab, tab, "/* verilator lint_off WIDTH */ reg_RW%d = {%d{$random}};".format(i, (addrWidth - 1) / 32 + 1)))) ++
      Seq(Seq(tab, "end"),
          Seq("`endif"))
    val assigns =
       (conf.readers.indices map (i => Seq(tab, "assign", s"R${i}_data = reg_R${i};"))) ++
       (conf.readwriters.indices map (i => Seq(tab, "assign", s"RW${i}_rdata = reg_RW${i};")))
    val always = (conf.readers.indices map (i => s"R${i}_clk" ->
      Seq(Seq(tab, tab, s"if (R${i}_en)", s"reg_R${i} <= ram[R${i}_addr];"))
    )) ++ (conf.writers.zipWithIndex map { case (w, i) => s"W${i}_clk" -> (
      w.head == 'm' match {
        case false => Seq(Seq(tab, tab, s"if (W${i}_en)", s"ram[W${i}_addr] <= W${i}_data;"))
        case true => (0 until maskWidth) map { k =>
          val range = s"${(k + 1) * conf.maskGran - 1} : ${k * conf.maskGran}"
          Seq(tab, tab, s"if (W${i}_en && W${i}_mask[$k])",
                        s"ram[W${i}_addr][$range] <= W${i}_data[$range];")
        }
      }
    )}) ++ (conf.readwriters.zipWithIndex map { case (w, i) => s"RW${i}_clk" -> (
      w.head == 'm' match {
        case false => Seq(
          Seq(tab, tab, s"if (RW${i}_en) begin"),
          Seq(tab, tab, tab, s"reg_RW${i} <= ram[RW${i}_addr];"),
          Seq(tab, tab, tab, s"if (RW${i}_wmode)", s"ram[RW${i}_addr] <= RW${i}_wdata;"),
          Seq(tab, tab, "end"))
        case true => Seq(
          Seq(tab, tab, s"if (RW${i}_en) begin"),
          Seq(tab, tab, tab, s"reg_RW${i} <= ram[RW${i}_addr];")
        ) ++ ((0 until maskWidth) map { k =>
          val range = s"${(k + 1) * conf.maskGran - 1} : ${k * conf.maskGran}"
          Seq(tab, tab, tab, s"if (RW${i}_wmode && RW${i}_wmask[${k}])",
                             s"ram[RW${i}_addr][$range] <= RW${i}_wdata[$range];")
        }) ++ Seq(
          Seq(tab, tab, "end")
        )
      }
    )})
    writer write s"""
module ${conf.name}(
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

class StroberVerilogEmitter(confFile: File, macroFile: File, pathFile: File) extends firrtl.VerilogEmitter {
  override def emit(state: firrtl.CircuitState, writer: Writer) {
    super.emit(state, writer)

    val seqMems = MemConfReader(confFile)
    val macroWriter = new FileWriter(macroFile)
    seqMems foreach StroberMacroEmitter(macroWriter)
    macroWriter.close

    val pathWriter = new FileWriter(pathFile)
    val insts = new firrtl.analyses.InstanceGraph(state.circuit)
    val paths = seqMems foreach { m =>
      (insts findInstancesInHierarchy m.name) foreach { is =>
        val path = is map (_.name) mkString "."
        pathWriter write s"${m.name} $path\n"
      }
    }
    pathWriter.close
  }
}
