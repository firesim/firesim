// See LICENSE for license details.

package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils._
import firrtl.passes.MemPortUtils._
import WrappedExpression.weq
import midas.passes.Utils._
import strober.core._
import java.io.StringWriter
import mdf.macrolib.SRAMMacro

class AddDaisyChains(
    meta: StroberMetaData,
    srams: Map[String, SRAMMacro])
   (implicit param: freechips.rocketchip.config.Parameters) extends firrtl.passes.Pass {
  override def name = "[strober] Add Daisy Chains"

  implicit def expToString(e: Expression): String = e.serialize

  private def generateChain(chainGen: () => chisel3.Module,
                            namespace: Namespace,
                            chainMods: DefModules,
                            instIdx: Int = 0)
                            (implicit chainType: ChainType.Value) = {
    val chirrtl = Parser parse (chisel3.Driver emit chainGen)
    val circuit = renameMods((new MiddleFirrtlCompiler compile (
      CircuitState(chirrtl, ChirrtlForm), Nil)).circuit, namespace)
    chainMods ++= circuit.modules
    Seq(WDefInstance(NoInfo, chainRef(instIdx).name, circuit.main, ut),
        IsInvalid(NoInfo, chainRef(instIdx)))
  }
 
  private def chainRef(i: Int = 0)(implicit chainType: ChainType.Value) =
    wref(s"${chainType.toString.toLowerCase}_$i", ut, InstanceKind)

  private def chainIo(i: Int = 0)(implicit chainType: ChainType.Value) =
    wsub(chainRef(i), "io")

  private def chainDataIo(pin: String, i: Int = 0)(implicit chainType: ChainType.Value) =
    wsub(wsub(chainIo(i), "dataIo"), pin)

  private def daisyPort(pin: String, idx: Int = 0)(implicit chainType: ChainType.Value) =
    wsub(widx(wsub(wref("daisy"), s"${chainType.toString.toLowerCase}"), idx), pin)

  private def childDaisyPort(child: String)(pin: String, idx: Int = 0)(implicit chainType: ChainType.Value) =
    wsub(widx(wsub(wsub(wref(child), "daisy"), s"${chainType.toString.toLowerCase}"), idx), pin)

  type DefModules = collection.mutable.ArrayBuffer[DefModule]
  type Statements = collection.mutable.ArrayBuffer[Statement]
  type Readers = collection.mutable.HashMap[String, Seq[String]]
  type Netlist = collection.mutable.HashMap[String, Expression]
  type ChainModSet = collection.mutable.HashSet[String]

  private def bigRegFile(s: DefMemory) =
    s.readLatency == 0 && s.depth.toInt >= 32 && bitWidth(s.dataType) >= 32

  private def collect(chainType: ChainType.Value, chains: Statements)(s: Statement): Statement = {
    chainType match {
      // TODO: do bfs from inputs
      case ChainType.Trace => s match {
        case s: WDefInstance if srams contains s.module =>
          chains += s
        case s: DefMemory if s.readLatency == 1 =>
          chains += s
        case _ =>
      }
      case ChainType.Regs => s match {
        case s: DefRegister =>
          chains += s
        case s: DefMemory if s.readLatency == 0 && !bigRegFile(s) =>
          chains += s
        case _ =>
      }
      case ChainType.RegFile => s match {
        case s: DefMemory if bigRegFile(s) =>
          chains += s
        case _ =>
      }
      case ChainType.SRAM => s match {
        case s: WDefInstance if srams contains s.module =>
          chains += s
        case s: DefMemory if s.readLatency == 1 =>
          chains += s
        case s: DefMemory if s.readLatency > 0 =>
          error("${s.info}: This type of memory is not supported")
        case _ =>
      }
      case ChainType.Cntr =>
    }
    s map collect(chainType, chains)
  }

  private def buildNetlist(netlist: Netlist)(s: Statement): Statement = {
    s match {
      case s: Connect =>
        netlist(s.loc) = s.expr
      case s: DefNode =>
        netlist(s.name) = s.value
      case s =>
    }
    s map buildNetlist(netlist)
  }

  private def insertRegChains(m: Module,
                              namespace: Namespace,
                              netlist: Netlist,
                              readers: Readers,
                              chainMods: DefModules,
                              hasChain: ChainModSet)
                              (implicit chainType: ChainType.Value) = {
    def sumWidths(stmts: Statements): Int = (stmts foldLeft 0){
      case (sum, s: DefRegister) =>
        sum + bitWidth(s.tpe).toInt
      case (sum, s: DefMemory) if s.readLatency == 0 && !bigRegFile(s) =>
        sum + bitWidth(s.dataType).toInt * s.depth.toInt
      case (sum, s: DefMemory) if s.readLatency == 1 =>
        sum + bitWidth(s.dataType).toInt * (s.readers.size + s.readwriters.size)
      case (sum, s: WDefInstance) =>
        val sram = srams(s.module)
        sum + sram.width * (sram.ports filter (_.output.nonEmpty)).size
    }
    def daisyConnects(regs: Seq[Expression], daisyLen: Int, daisyWidth: Int) = {
      (((0 until daisyLen) foldRight (Seq[Connect](), 0, 0)){case (i, (stmts, index, offset)) =>
        def loop(total: Int, index: Int, offset: Int, wires: Seq[Expression]): (Int, Int, Seq[Expression]) = {
          (daisyWidth - total) match {
            case 0 => (index, offset, wires)
            case margin if index < regs.size =>
              val reg = regs(index)
              val width = bitWidth(reg.tpe).toInt - offset
              if (width <= margin) {
                loop(total + width, index + 1, 0, wires :+ bits(reg, width-1, 0))
              } else {
                loop(total + margin, index, offset + margin, wires :+ bits(reg, width-1, width-margin))
              }
            case margin =>
              loop(total + margin, index, offset, wires :+ UIntLiteral(0, IntWidth(margin)))
          }
        }
        val (idx, off, wires) = loop(0, index, offset, Nil)
        // "<daisy_chain>.io.dataIo.data[i] <- cat(wires)"
        (stmts :+ Connect(NoInfo, widx(chainDataIo("data"), i), cat(wires)), idx, off)
      })._1
    }

    val chainElems = new Statements
    collect(chainType, chainElems)(m.body)
    meta.chains(chainType)(m.name) = chainElems
    if (chainElems.isEmpty) Nil
    else {
      lazy val chain = new RegChain()(param alterPartial ({
        case DataWidth => sumWidths(chainElems) }))
      val instStmts = generateChain(() => chain, namespace, chainMods)
      val clock = m.ports flatMap (p =>
        create_exps(wref(p.name, p.tpe))) find (_.tpe ==  ClockType)
      val portConnects = Seq(
        // <daisy_chain>.clock <- clock
        Connect(NoInfo, wsub(chainRef(), "clock"), clock.get),
        // <daisy_chain>.reset <- daisyReset
        Connect(NoInfo, wsub(chainRef(), "reset"), wref("daisyReset")),
        // <daisy_chain>.io.stall <- not(targetFire)
        Connect(NoInfo, wsub(chainIo(), "stall"), not(wref("targetFire"))),
        // <daiy_port>.out <- <daisy_chain>.io.dataIo.out
        Connect(NoInfo, daisyPort("out"), chainDataIo("out"))
      )
      hasChain += m.name
      val stmts = new Statements
      val regs = meta.chains(chainType)(m.name) flatMap {
        case s: DefRegister => create_exps(s.name, s.tpe)
        case s: DefMemory => chainType match {
          case ChainType.Regs =>
            val rs = (0 until s.depth.toInt) map (i => s"scan_$i")
            val mem = s.copy(readers = s.readers ++ rs)
            val exps = rs map (r => create_exps(memPortField(mem, r, "data")))
            readers(s.name) = rs
            ((0 until exps.head.size) foldLeft Seq[Expression]())(
              (res, i) => res ++ (exps map (_(i))))
          case ChainType.Trace =>
            (s.readers flatMap (r =>
              create_exps(memPortField(s, r, "data")).reverse)) ++
            (s.readwriters flatMap (rw =>
              create_exps(memPortField(s, rw, "rdata")).reverse))
        }
        case s: WDefInstance =>
          val memref = wref(s.name, s.tpe, InstanceKind)
          val ports = srams(s.module).ports filter (_.output.nonEmpty)
          ports map (p => wsub(memref, p.output.get.name))
      }
      stmts ++ instStmts ++ portConnects ++ daisyConnects(regs, chain.daisyLen, chain.daisyWidth)
    }
  }

  private def insertSRAMChains(m: Module,
                               namespace: Namespace,
                               netlist: Netlist,
                               repl: Netlist,
                               chainMods: DefModules,
                               hasChain: ChainModSet)
                               (implicit chainType: ChainType.Value) = {
    def sumWidths(stmts: Statements) = (stmts foldLeft (0, 0, 0)){
      case ((sum, max, depth), s: DefMemory) =>
        val width = bitWidth(s.dataType).toInt
        (sum + width, max max width, depth max s.depth.toInt)
      case ((sum, max, depth), s: WDefInstance) =>
        val sram = srams(s.module)
        (sum + sram.width, max max sram.width, depth max sram.depth.toInt)
    }
    def daisyConnect(stmts: Statements)(elem: (Statement, Int)): Expression = {
      val (data, addr, ce, re) = (elem._1: @unchecked) match {
        case s: WDefInstance =>
          val memref = wref(s.name, s.tpe, InstanceKind)
          val port = (srams(s.module).ports filter (_.output.nonEmpty)).head
          (wsub(memref, port.output.get.name),
           wsub(memref, port.address.name),
           port.chipEnable match {
             case Some(ce) => wsub(memref, ce.name) -> inv(ce.polarity)
             case None => EmptyExpression -> false
           },
           port.readEnable match {
             case Some(re) => wsub(memref, re.name) -> inv(re.polarity)
             case None => EmptyExpression -> false
           })
        case s: DefMemory if s.readers.nonEmpty => (
          memPortField(s, s.readers.head, "data"),
          memPortField(s, s.readers.head, "addr"),
          memPortField(s, s.readers.head, "en") -> false,
          EmptyExpression -> false
        )
        case s: DefMemory => (
          memPortField(s, s.readwriters.head, "rdata"),
          memPortField(s, s.readwriters.head, "addr"),
          memPortField(s, s.readwriters.head, "en") -> false,
          not(memPortField(s, s.readwriters.head, "wmode")) -> false
        )
      }
      val addrIn = wsub(widx(wsub(chainIo(), "addrIo"), elem._2), "in")
      val addrOut = wsub(widx(wsub(chainIo(), "addrIo"), elem._2), "out")
      def memPortConnects(s: Statement): Statement = {
        s match {
          case Connect(info, loc, expr) => kind(loc) match {
            case MemKind | InstanceKind if weq(loc, ce._1) =>
              repl(loc.serialize) = (
                if (ce._2) and(not(wsub(addrOut, "valid")), expr) // inverted port
                else or(wsub(addrOut, "valid"), expr))
            case MemKind | InstanceKind if weq(loc, re._1) =>
              repl(loc.serialize) = (
                if (re._2) and(not(wsub(addrOut, "valid")), expr) // inverted port
                else or(wsub(addrOut, "valid"), expr))
            case MemKind | InstanceKind if weq(loc, addr) =>
              repl(loc.serialize) = Mux(wsub(addrOut, "valid"), wsub(addrOut, "bits"), expr, ut)
            case _ =>
          }
          case _ =>
        }
        s map memPortConnects
      }
      memPortConnects(m.body)
      if (chainType == ChainType.SRAM) stmts ++= Seq(
        // <daisy_chain>.io.addr[i].in.bits <- <memory>.data
        Connect(NoInfo, wsub(addrIn, "bits"), netlist(addr)),
        // <daisy_chain>.io.addr[i].in.valid <- <memory>.en
        Connect(NoInfo, wsub(addrIn, "valid"), (ce._1, re._1) match {
          case (EmptyExpression, ex) =>
            if (re._2) not(netlist(ex)) else netlist(ex)
          case (ex, EmptyExpression) =>
            if (ce._2) not(netlist(ex)) else netlist(ex)
          case (ex1, ex2) => and(
            if (ce._2) not(netlist(ex1)) else netlist(ex1),
            if (re._2) not(netlist(ex1)) else netlist(ex1))
        })
      )
      cat(create_exps(data).reverse)
    }

    def daisyConnects(elems: Statements, width: Int, daisyLen: Int, daisyWidth: Int): Seq[Statement] = {
      if (netlist.isEmpty) buildNetlist(netlist)(m.body)
      val stmts = new Statements
      val dataCat = cat(elems.zipWithIndex map daisyConnect(stmts))
      val dataConnects = {
        ((0 until daisyLen foldRight (Seq[Connect](), width - 1)){ case (i, (stmts, high)) =>
          val low = (high - daisyWidth + 1) max 0
          val input = bits(dataCat, high, low)
          (stmts :+ (daisyWidth - (high - low + 1) match {
            case 0 =>
              // <daisy_chain>.io.dataIo.data[i] <- <memory>.data(high, low)
              Connect(NoInfo, widx(chainDataIo("data"), i), input)
            case margin =>
              val pad = UIntLiteral(0, IntWidth(margin))
              // <daisy_chain>.io.dataIo.data[i] <- cat(<memory>.data(high, low), pad)
              Connect(NoInfo, widx(chainDataIo("data"), i), cat(Seq(input, pad)))
          }), high - daisyWidth)
        })._1
      }
      dataConnects ++ stmts 
    }

    val chainElems = new Statements
    collect(chainType, chainElems)(m.body)
    meta.chains(chainType)(m.name) = chainElems
    if (chainElems.isEmpty) Nil
    else {
      val (sum, max, depth) = sumWidths(chainElems)
      lazy val chain = new SRAMChain()(param alterPartial ({
        case DataWidth => sum
        case MemWidth  => max
        case MemDepth  => depth
        case MemNum    => chainElems.size
        case SeqRead   => chainType == ChainType.SRAM
      }))
      val instStmts = generateChain(() => chain, namespace, chainMods)
      val clock = m.ports flatMap (p =>
        create_exps(wref(p.name, p.tpe))) find (_.tpe ==  ClockType)
      val portConnects = Seq(
        // <daisy_chain>.clock <- clock
        Connect(NoInfo, wsub(chainRef(), "clock"), clock.get),
        // <daisy_chain>.reset <- daisyReset
        Connect(NoInfo, wsub(chainRef(), "reset"), wref("daisyReset")),
        // <daisy_chain>.io.stall <- not(targetFire)
        Connect(NoInfo, wsub(chainIo(), "stall"), not(wref("targetFire"))),
        // <daisy_chain>.io.restart <- <daisy_port>.restart
        Connect(NoInfo, wsub(chainIo(), "restart"), daisyPort("restart")),
         // <daiy_port>.out <- <daisy_chain>.io.dataIo.out
        Connect(NoInfo, daisyPort("out"), chainDataIo("out"))
      )
      hasChain += m.name
      instStmts ++ portConnects ++ daisyConnects(chainElems, sum, chain.daisyLen, chain.daisyWidth)
    }
  }

  private def insertChains(m: Module,
                           namespace: Namespace,
                           netlist: Netlist,
                           readers: Readers,
                           repl: Netlist,
                           chainMods: DefModules,
                           hasChainMap: Map[ChainType.Value, ChainModSet])
                           (t: ChainType.Value) = {
    implicit val chainType = t
    val hasChain = hasChainMap(chainType)
    val chainStmts = chainType match {
      case ChainType.Trace | ChainType.Regs | ChainType.Cntr =>
        insertRegChains(m, namespace, netlist, readers, chainMods, hasChain)
      case ChainType.SRAM | ChainType.RegFile =>
        insertSRAMChains(m, namespace, netlist, repl, chainMods, hasChain)
    }
    val chainNum = 1
    // Filter children who have daisy chains
    val childrenWithChains = meta.childInsts(m.name) filter (
      x => hasChain(meta.instModMap(x, m.name)))
    val invalids = childrenWithChains flatMap (c => Seq(
      IsInvalid(NoInfo, childDaisyPort(c)("in")),
      IsInvalid(NoInfo, childDaisyPort(c)("out"))))
    val connects = (childrenWithChains foldLeft (None: Option[String], Seq[Connect]())){
      case ((None, stmts), child) if !hasChain(m.name) =>
        // <daisy_port>.out <- <child>.<daisy_port>.out
        (Some(child), stmts :+ Connect(NoInfo, daisyPort("out"), childDaisyPort(child)("out")))
      case ((None, stmts), child) =>
        // <daisy_chain>.io.dataIo.in <- <child>.<daisy_port>.out
        (Some(child), stmts :+ Connect(NoInfo, chainDataIo("in", chainNum-1), childDaisyPort(child)("out")))
      case ((Some(p), stmts), child) =>
        // <prev_child>.<daisy_port>.io.in <- <child>.<daisy_port>.out
        (Some(child), stmts :+ Connect(NoInfo, childDaisyPort(p)("in"), childDaisyPort(child)("out")))
    } match {
      case (None, stmts) if !hasChain(m.name) => stmts
      case (None, stmts) =>
        // <daisy_chain>.io.dataIo.in <- <daisy_port>.in
        stmts :+ Connect(NoInfo, chainDataIo("in", chainNum-1), daisyPort("in"))
      case (Some(p), stmts) =>
        // <prev_child>.<daisy_port>.in <- <daisy_port>.in
        stmts :+ Connect(NoInfo, childDaisyPort(p)("in"), daisyPort("in"))
    }
    if (childrenWithChains.nonEmpty) hasChain += m.name
    Block(invalids ++ chainStmts ++ connects)
  }

  def updateStmts(readers: Readers,
                  repl: Netlist,
                  clock: Option[Expression],
                  stmts: Statements)
                  (s: Statement): Statement = s match {
    // Connect restart pins
    case s: WDefInstance if !(srams contains s.module) => Block(Seq(s,
      IsInvalid(NoInfo, wsub(wref(s.name), "daisy")),
      Connect(NoInfo, wsub(wref(s.name), "daisyReset"), wref("daisyReset", BoolType)),
      Connect(NoInfo, childDaisyPort(s.name)("restart")(ChainType.SRAM), daisyPort("restart")(ChainType.SRAM)),
      Connect(NoInfo, childDaisyPort(s.name)("restart")(ChainType.RegFile), daisyPort("restart")(ChainType.RegFile))
    ))
    case s: DefMemory => readers get s.name match {
      case None => s
      case Some(rs) =>
        val mem = s.copy(readers = s.readers ++ rs)
        Block(mem +: (rs.zipWithIndex flatMap { case (r, i) =>
          val addr = UIntLiteral(i, IntWidth(chisel3.util.log2Up(s.depth.toInt)))
          Seq(Connect(NoInfo, memPortField(mem, r, "clk"), clock.get),
              Connect(NoInfo, memPortField(mem, r, "en"), one),
              Connect(NoInfo, memPortField(mem, r, "addr"), addr))
        }))
    }
    case s: Connect => kind(s.loc) match {
      // Replace sram inputs
      case MemKind | InstanceKind => repl get s.loc.serialize match {
        case Some(expr) => 
          stmts += s.copy(expr = expr)
          EmptyStmt
        case None => s
      }
      case _ => s
    }
    case s => s map updateStmts(readers, repl, clock, stmts)
  }

  private def transform(namespace: Namespace,
                        daisyType: Type,
                        chainMods: DefModules,
                        hasChain: Map[ChainType.Value, ChainModSet])
                        (m: DefModule) = m match {
    case m: Module if !(srams contains m.name) =>
      val netlist = new Netlist
      val readers = new Readers
      val stmts = new Statements
      val repl = new Netlist
      val clock = m.ports flatMap (p =>
        create_exps(wref(p.name, p.tpe))) find (_.tpe ==  ClockType)
      val daisyReset = Port(NoInfo, "daisyReset", Input, BoolType)
      val daisyPort = Port(NoInfo, "daisy", Output, daisyType)
      val daisyInvalid = IsInvalid(NoInfo, wref("daisy", daisyType))
      val chainStmts = (ChainType.values.toList map
        insertChains(m, namespace, netlist, readers, repl, chainMods, hasChain))
      val bodyx = updateStmts(readers, repl, clock, stmts)(m.body)
      m.copy(ports = m.ports ++ Seq(daisyReset, daisyPort),
             body = Block(Seq(daisyInvalid, bodyx) ++ chainStmts ++ stmts))
    case m => m
  }

  def run(c: Circuit) = {
    val namespace = Namespace(c)
    val chainMods = new DefModules
    val hasChain = (ChainType.values.toList map (_ -> new ChainModSet)).toMap
    val chirrtl = Parser parse (chisel3.Driver emit (() => new core.DaisyBox))
    val daisybox = (new MiddleFirrtlCompiler compile (
      CircuitState(chirrtl, ChirrtlForm), new StringWriter)).circuit
    val daisyType = daisybox.modules.head.ports.find(_.name == "io").get.tpe
    val targetMods = postorder(c, meta)(transform(namespace, daisyType, chainMods, hasChain))
    c.copy(modules = chainMods ++ targetMods)
  }
}
