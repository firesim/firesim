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
    val bbInst = WDefInstance("tdpMem", bbName)
    val ports = memType(proto).fields.map {
      case Field(name, Flip, tpe) => Port(proto.info, name, Input, tpe)
    }

    val canonicalizedPorts =
      proto.readwriters.map(rw => (rw, true, true)) ++
      proto.readers.map(r => (r, true, false)) ++
      proto.writers.map(w => (w, true, false))

    val portConns = (canonicalizedPorts zip Seq("a", "b")).flatMap {
      case ((pName, hasR, hasW), bbSuffix)  =>
        def bbP(p: String) = WSubField(WRef(bbInst.name), p + bbSuffix)
        def topSF(sf: String) = WSubField(WRef(pName), sf)
        val generalConns = Seq(Connect(proto.info, bbP("clk"), topSF("clk")),
                               Connect(proto.info, bbP("en"), topSF("en")),
                               Connect(proto.info, bbP("addr"), topSF("addr")))
        val rConns = Seq(Connect(proto.info, topSF("data"), bbP("do")))
        val wConns = Seq(Connect(proto.info, bbP("we"), fame.And(topSF("en"), topSF("mask"))),
                         Connect(proto.info, bbP("di"), topSF("wdata")))
        val rwConns = Seq(Connect(proto.info, bbP("we"), fame.And(topSF("wmode"), topSF("wmask"))),
                          Connect(proto.info, bbP("di"), topSF("wdata")),
                          Connect(proto.info, topSF("rdata"), bbP("do")))
        generalConns ++ rConns.filter(_ => hasR && !hasW) ++ wConns.filter(_ => !hasR && hasW) ++ rwConns.filter(_ => hasR && hasW)
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

  private def singleRWWrapper(proto: DefMemory, wrapperName: String, bbName: String): Module = {
    require(proto.readers.isEmpty && proto.writers.isEmpty && proto.readwriters.length == 1)

    val bbInst = WDefInstance("singleRWMem", bbName)
    val ports = memType(proto).fields.map {
      case Field(name, Flip, tpe) => Port(proto.info, name, Input, tpe)
    }

    def bbP(p: String) = WSubField(WRef(bbInst.name), p)
    def topSF(sf: String) = WSubField(WRef(proto.readwriters.head), sf)
    val portConns = Seq(Connect(proto.info, bbP("we"), fame.And(topSF("wmode"), topSF("wmask"))),
                        Connect(proto.info, bbP("clk"), topSF("clk")),
                        Connect(proto.info, bbP("en"), topSF("en")),
                        Connect(proto.info, bbP("addr"), topSF("addr")),
                        Connect(proto.info, bbP("di"), topSF("wdata")),
                        Connect(proto.info, topSF("rdata"), bbP("do")))

    Module(proto.info, wrapperName, ports, Block(bbInst +: portConns))
  }

  private def singleRWBlackbox(proto: DefMemory, bbDefname: String, bbName: String): ExtModule = {
    val dWidth: BigInt = proto.dataType match {
      case GroundType(IntWidth(i)) => i
    }
    ExtModule(
      info = proto.info,
      name = bbName,
      ports = Seq(Port(NoInfo, "clk", Input, ClockType),
                  Port(NoInfo, "en", Input, BoolType),
                  Port(NoInfo, "we", Input, BoolType),
                  Port(NoInfo, "addr", Input, UIntLiteral(proto.depth-1).tpe),
                  Port(NoInfo, "di", Input, proto.dataType),
                  Port(NoInfo, "do", Output, proto.dataType)),
      defname = bbDefname,
      params = Seq(IntParam("WIDTH", dWidth), IntParam("DEPTH", proto.depth), IntParam("AWIDTH", (proto.depth-1).bitLength))
    )
  }

  private def singleRWTemplate(name: String) =
    s"""|`ifndef ${name.toUpperCase()}
        |`define ${name.toUpperCase()}
        |module ${name} #(parameter WIDTH = 36, parameter DEPTH = 1024, parameter AWIDTH = 10) (
        |   clk,en,we,addr,di,do
        |);
        |   input clk, en, we;
        |   input [AWIDTH-1:0] addr;
        |   input [WIDTH-1:0] di;
        |   output [WIDTH-1:0] do;
        |   reg [WIDTH-1:0] ram [DEPTH-1:0];
        |   reg [WIDTH-1:0] do;
        |   always @(posedge clk) begin
        |      if (en) begin
        |         if (we)
        |            ram[addr] <= di;
        |         do <= ram[addr];
        |      end
        |   end
        |endmodule
        |`endif
        |""".stripMargin

  private def onStmt(modNS: Namespace, bbDefnames: Seq[String], newMods: mutable.Map[DefMemory, (Module, ExtModule)])(stmt: Statement): Statement = stmt match {
    case dm: DefMemory if (dm.readUnderWrite == ReadUnderWrite.New) => dm
    case dm @ DefMemory(_, _, _, _, 1, 1, rp, wp, rwp, _) if ((rp ++ wp ++ rwp).length <= 2) =>
      val wrapperName = modNS.newName("BRAMWrapper")
      val bbName = modNS.newName("ParameterizedXilinxTemplateBRAM")
      if (rp.length == 0 && wp.length == 0 && rwp.length == 1) {
        newMods(dm) = (singleRWWrapper(dm, wrapperName, bbName), singleRWBlackbox(dm, bbDefnames(0), bbName))
        WDefInstance(dm.name, wrapperName)
      } else if ((rp ++ wp ++ rwp).length <= 2) {
        newMods(dm) = (tdpWrapper(dm, wrapperName, bbName), tdpBlackbox(dm, bbDefnames(1), bbName))
        WDefInstance(dm.name, wrapperName)
      } else {
        dm
      }
    case s => s.map(onStmt(modNS, bbDefnames, newMods))
  }

  def execute(state: CircuitState): CircuitState = {
    val cName = CircuitName(state.circuit.main)
    val modNS = Namespace(state.circuit)

    val bbDefnames = Seq(modNS.newName("XilinxTemplateBRAM"), modNS.newName("XilinxTemplateTDPBRAM"))
    val bbVerilogs = Seq(singleRWTemplate(bbDefnames(0)), tdpTemplate(bbDefnames(1)))

    val newModsByMem = new mutable.HashMap[DefMemory, (Module, ExtModule)]
    val transformedMods = state.circuit.modules.map {
      case m: Module => m.copy(body = onStmt(modNS, bbDefnames, newModsByMem)(m.body))
      case m => m
    }

    val newMods = newModsByMem.flatMap { case (m, (w, bb)) => Seq(w, bb) }
    val newAnnos = newModsByMem.map({ case (k, v) => (k.readwriters.length, v) }).collect {
      case (nP, (w, bb)) => BlackBoxInlineAnno(ModuleName(bb.name, cName), s"${bbDefnames(nP-1)}.v", bbVerilogs(nP-1))
    }

    state.copy(circuit = state.circuit.copy(modules = transformedMods ++ newMods), annotations = state.annotations ++ newAnnos)
  }
}
