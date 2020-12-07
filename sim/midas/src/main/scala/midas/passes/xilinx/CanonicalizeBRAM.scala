package midas
package passes
package xilinx

import firrtl._
import firrtl.ir._

import firrtl.Mappers._
import firrtl.annotations._
import firrtl.Utils.BoolType
import firrtl.options.Dependency

import firrtl.passes._
import firrtl.passes.MemPortUtils._
import firrtl.transforms.BlackBoxInlineAnno

import collection.mutable

object CanonicalizeBRAM extends Transform with DependencyAPIMigration {

  override val prerequisites = firrtl.stage.Forms.LowForm
  override val optionalPrerequisites = Nil
  override val optionalPrerequisiteOf = Seq(Dependency(memlib.VerilogMemDelays))
  override def invalidates(a: Transform) = a match {
    case InferTypes | ResolveKinds | ResolveFlows | LowerTypes => true
    case _ => false
  }

  private def tdpWrapper(proto: DefMemory, wrapperName: String, bbName: String): Module = {
    require(proto.readers.isEmpty && proto.writers.isEmpty && proto.readwriters.length == 2)

    val bbInst = WDefInstance("tdpMem", bbName)
    val ports = memType(proto).fields.map {
      case Field(name, Flip, tpe) => Port(proto.info, name, Input, tpe)
    }

    val portConns = (proto.readwriters zip Seq("a", "b")).flatMap {
      case (topP, bbSuffix)  =>
        def bbP(p: String) = WSubField(WRef(bbInst.name), p + bbSuffix)
        def topSF(sf: String) = WSubField(WRef(topP), sf)
        Seq(Connect(proto.info, bbP("we"), fame.And(topSF("wmode"), topSF("wmask"))),
	    Connect(proto.info, bbP("clk"), topSF("clk")),
	    Connect(proto.info, bbP("en"), topSF("en")),
	    Connect(proto.info, bbP("addr"), topSF("addr")),
	    Connect(proto.info, bbP("di"), topSF("wdata")),
            Connect(proto.info, topSF("rdata"), bbP("do")))
    }

    Module(proto.info, wrapperName, ports, Block(bbInst +: portConns))
  }

  private def tdpBlackbox(proto: DefMemory, bbDefname: String, bbName: String): ExtModule = {
    val dWidth: BigInt = proto.dataType match {
      case GroundType(IntWidth(i)) => i
    }
    ExtModule(
      info = proto.info,
      name = bbName,
      ports = (Seq("a", "b").flatMap {
        bbP =>
          Seq(Port(NoInfo, "clk" + bbP, Input, ClockType),
              Port(NoInfo, "en" + bbP, Input, BoolType),
              Port(NoInfo, "we" + bbP, Input, BoolType),
              Port(NoInfo, "addr" + bbP, Input, UIntLiteral(proto.depth-1).tpe),
              Port(NoInfo, "di" + bbP, Input, proto.dataType),
              Port(NoInfo, "do" + bbP, Output, proto.dataType))
      }),
      defname = bbDefname,
      params = Seq(IntParam("WIDTH", dWidth), IntParam("DEPTH", proto.depth), IntParam("AWIDTH", (proto.depth-1).bitLength))
    )
  }

  private def tdpTemplate(name: String) =
    s"""|`ifndef ${name.toUpperCase()}
        |`define ${name.toUpperCase()}
        |module ${name} #(parameter WIDTH = 36, parameter DEPTH = 1024, parameter AWIDTH = 10) (
        |   clka,clkb,ena,enb,wea,web,addra,addrb,dia,dib,doa,dob
        |);
        |   input clka, clkb, ena, enb, wea, web;
        |   input [AWIDTH-1:0] addra, addrb;
        |   input [WIDTH-1:0] dia, dib;
        |   output [WIDTH-1:0] doa, dob;
        |   reg [WIDTH-1:0] ram [DEPTH-1:0];
        |   reg [WIDTH-1:0] doa, dob;
        |   always @(posedge clka) begin
        |      if (ena) begin
        |         if (wea)
        |            ram[addra] <= dia;
        |         doa <= ram[addra];
        |      end
        |   end
        |   always @(posedge clkb) begin
        |      if (enb) begin
        |         if (web)
        |            ram[addrb] <= dib;
        |         dob <= ram[addrb];
        |      end
        |   end
        |endmodule
        |`endif
        |""".stripMargin

  private def onStmt(modNS: Namespace, bbDefname: String, newMods: mutable.Map[DefMemory, (Module, ExtModule)])(stmt: Statement): Statement = stmt match {
    case dm: DefMemory if (dm.readUnderWrite == ReadUnderWrite.New) => dm
    case dm @ DefMemory(_, _, _, _, 1, 1, Nil, Nil, rwp, _) if (rwp.length == 2) =>
      val wrapperName = modNS.newName("TDPMemWrapper")
      val bbName = modNS.newName("ParameterizedXilinxTemplateTDPBRAM")
      val wrapper = tdpWrapper(dm, wrapperName, bbName)
      val bb = tdpBlackbox(dm, bbDefname, bbName)
	newMods(dm) = (wrapper, bb)
      WDefInstance(dm.name, wrapper.name)
    case s => s.map(onStmt(modNS, bbDefname, newMods))
  }

  def execute(state: CircuitState): CircuitState = {
    val cName = CircuitName(state.circuit.main)
    val modNS = Namespace(state.circuit)
    val bbDefname = modNS.newName("XilinxTemplateTDPBRAM")
    val newMemMods = new mutable.HashMap[DefMemory, (Module, ExtModule)]
    val transformedMods = state.circuit.modules.map {
      case m: Module => m.copy(body = onStmt(modNS, bbDefname, newMemMods)(m.body))
      case m => m
    }
    val newMods = newMemMods.flatMap { case (m, (w, bb)) => Seq(w, bb) }
    val verilogAnnos = newMemMods.headOption.map {
      case (m, (w, bb)) =>
        BlackBoxInlineAnno(ModuleName(bb.name, cName), s"${bbDefname}.v", tdpTemplate(bbDefname))
    }
    state.copy(circuit = state.circuit.copy(modules = transformedMods ++ newMods), annotations = state.annotations ++ verilogAnnos)
  }
}
