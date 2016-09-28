package strober
package passes

import Utils._
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils._
import firrtl.passes.bitWidth
import firrtl.passes.MemPortUtils._
import firrtl.passes.LowerTypes.loweredName
import WrappedExpression.weq

private[passes] class AddDaisyChains(conf: java.io.File) extends firrtl.passes.Pass {
  def name = "[strober] Add Daisy Chains"

  implicit def expToString(e: Expression): String = e.serialize

  private class ChainCompiler extends Compiler {
    def transforms(writer: java.io.Writer) = Seq(
      new Chisel3ToHighFirrtl,
      new IRToWorkingIR,
      new ResolveAndCheck,
      new HighFirrtlToMiddleFirrtl,
      new EmitFirrtl(writer) // debugging
    )
  }

  private def generateChain(chainGen: () => chisel3.Module,
                            namespace: Namespace,
                            chainMods: DefModules,
                            instIdx: Int = 0)
                            (implicit chainType: ChainType.Value) = {
    val circuit = Parser parse (chisel3.Driver emit chainGen)
    val annotation = new Annotations.AnnotationMap(Nil)
    val output = new java.io.StringWriter
    val result = new ChainCompiler compile (circuit, annotation, output)
    val (modules, nameMap) = (result.circuit.modules foldLeft
      (Seq[DefModule](), Map[String, String]())){ case ((ms, map), m) =>
        val newMod = m match {
          // No copy method in DefModule
          case m: Module => m copy (name = namespace newName m.name)
        }
        ((ms :+ newMod), map + (m.name -> newMod.name))
    }
    def updateModName(s: Statement): Statement = s match {
      case s: WDefInstance => s copy (module = nameMap(s.module))
      case s => s map updateModName
    }
    chainMods ++= (modules map (_ map updateModName))
    Seq(
      WDefInstance(NoInfo, chainRef(instIdx).name, nameMap(circuit.main), ut),
      IsInvalid(NoInfo, chainRef(instIdx))
    )
  }
 
  private def chainRef(i: Int = 0)(implicit chainType: ChainType.Value) =
    wref(s"${chainType.toString.toLowerCase}_$i")

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

  private val seqMems = (MemConfReader(conf) map (m => m.name -> m)).toMap

  private def bigRegFile(s: DefMemory) =
    s.readLatency == 0 && s.depth >= 32 && bitWidth(s.dataType) >= 32

  private def collect(chainType: ChainType.Value, chains: Statements)(s: Statement): Statement = {
    chainType match {
      // TODO: do bfs from inputs
      case ChainType.Regs => s match {
        case s: DefRegister =>
          chains += s
        case s: DefMemory if !bigRegFile(s) =>
          chains += s
        case s: WDefInstance if seqMems contains s.module =>
          chains += s
        case _ =>
      }
      case ChainType.SRAM => s match {
        case s: WDefInstance if seqMems contains s.module =>
          chains += s
        case s: DefMemory if bigRegFile(s) =>
          chains += s
        case s: DefMemory if s.readLatency > 0 =>
          error("${s.info}: SRAMs should be transformed to Black bloxes")
        case _ =>
      }
      case ChainType.Trace =>
      case ChainType.Cntr =>
    }
    s map collect(chainType, chains)
  }

  private def buildNetlist(netlist: Netlist)(s: Statement): Statement = {
    s match {
      case s: Connect =>
        netlist(s.loc) = s.expr
      case s: PartialConnect =>
        netlist(s.loc) = s.expr
      case s: DefNode =>
        netlist(s.name) = s.value
      case s =>
    }
    s map buildNetlist(netlist)
  }

  private def insertRegChains(m: Module,
                              p: cde.Parameters,
                              namespace: Namespace,
                              netlist: Netlist,
                              readers: Readers,
                              chainMods: DefModules,
                              hasChain: ChainModSet)
                              (implicit chainType: ChainType.Value) = {
    def sumWidths(s: Statement): Int = s match {
      case s: DefRegister => bitWidth(s.tpe).toInt
      case s: DefMemory if !bigRegFile(s) =>
        s.depth * bitWidth(s.dataType).toInt
      case s: WDefInstance => seqMems get s.module match {
        case None => 0
        case Some(seqMem) =>
          val addrWidth = chisel3.util.log2Up(seqMem.depth.toInt)
          (seqMem.readers.size + seqMem.readwriters.size) * (addrWidth /*+ seqMem.width.toInt */)
      }
      case s: Block => (s.stmts foldLeft 0)(_ + sumWidths(_))
      case _ => 0
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
    chains(chainType)(m.name) = chainElems
    chainElems.nonEmpty match {
      case false => Nil
      case true =>
        lazy val chain = new RegChain()(p alter Map(DataWidth -> sumWidths(m.body)))
        val instStmts = generateChain(() => chain, namespace, chainMods)
        val clocks = m.ports flatMap (p =>
          create_exps(wref(p.name, p.tpe)) filter (_.tpe ==  ClockType))
        val portConnects = Seq(
          // <daisy_chain>.clock <- clock
          Connect(NoInfo, wsub(chainRef(), "clock"), clocks.head),
          // <daisy_chain>.reset <- daisyReset
          Connect(NoInfo, wsub(chainRef(), "reset"), wref("daisyReset")),
          // <daisy_chain>.io.stall <- not(targetFire)
          Connect(NoInfo, wsub(chainIo(), "stall"), not(wref("targetFire"))),
          // <daiy_port>.out <- <daisy_chain>.io.dataIo.out
          Connect(NoInfo, daisyPort("out"), chainDataIo("out"))
        )
        hasChain += m.name
        val stmts = new Statements
        lazy val mnamespace = Namespace(m)
        def insertBuf(name: String, value: Expression, en: Expression) = {
          val buf = wref(mnamespace newName name, value.tpe, RegKind)
          stmts ++= Seq(
            DefRegister(NoInfo, buf.name, buf.tpe, clocks.head, zero, buf),
            Connect(NoInfo, buf, Mux(en, value, buf, buf.tpe))
          )
          buf
        }
        val regs = chains(chainType)(m.name) flatMap {
          case s: DefRegister => create_exps(s.name, s.tpe)
          case s: DefMemory =>
            val rs = (0 until s.depth) map (i => s"scan_$i")
            val mem = s copy (readers = s.readers ++ rs)
            val exps = rs map (r => create_exps(memPortField(mem, r, "data")))
            readers(s.name) = rs
            ((0 until exps.head.size) foldLeft Seq[Expression]())(
              (res, i) => res ++ (exps map (_(i))))
          case s: WDefInstance =>
            if (netlist.isEmpty) buildNetlist(netlist)(m.body)
            val seqMem = seqMems(s.module)
            val mem = wref(s.name, s.tpe, InstanceKind)
            val en = (seqMem.readers.indices map (i => netlist(wsub(wsub(mem, s"R$i"), "en")))) ++
                     (seqMem.readwriters.indices map (i =>
                      and(netlist(wsub(wsub(mem, s"RW$i"), "en")),
                      not(netlist(wsub(wsub(mem, s"RW$i"), "wmode"))))
                     ))
            val addr = (seqMem.readers.indices map (i => wsub(wsub(mem, s"R$i"), "addr"))) ++
                   (seqMem.readwriters.indices map (i => wsub(wsub(mem, s"RW$i"), "addr")))
            val data = (seqMem.readers.indices map (i => wsub(wsub(mem, s"R$i"), "data"))) ++
                   (seqMem.readwriters.indices map (i => wsub(wsub(mem, s"RW$i"), "rdata")))
            (addr zip en map { case (a, e) =>
              insertBuf(s"${loweredName(a)}_buf", netlist(a), e)
            }) /* ++ data */
        }
        stmts ++ instStmts ++ portConnects ++ daisyConnects(regs, chain.daisyLen, chain.daisyWidth)
    }
  }

  private def insertSRAMChains(m: Module,
                               p: cde.Parameters,
                               namespace: Namespace,
                               netlist: Netlist,
                               repl: Netlist,
                               chainMods: DefModules,
                               hasChain: ChainModSet)
                               (implicit chainType: ChainType.Value) = {
    def daisyConnects(sram: Statement, daisyIdx: Int, daisyLen: Int, daisyWidth: Int) = {
      val (data, addr, en, wmode, width) = sram match {
        case s: WDefInstance if seqMems(s.module).readwriters.nonEmpty => (
          wsub(wsub(wref(s.name, s.tpe, InstanceKind), "RW0"), "rdata"),
          wsub(wsub(wref(s.name, s.tpe, InstanceKind), "RW0"), "addr"),
          wsub(wsub(wref(s.name, s.tpe, InstanceKind), "RW0"), "en"),
          wsub(wsub(wref(s.name, s.tpe, InstanceKind), "RW0"), "wmode"),
          seqMems(s.module).width.toInt)
        case s: WDefInstance => (
          wsub(wsub(wref(s.name, s.tpe, InstanceKind), "R0"), "data"),
          wsub(wsub(wref(s.name, s.tpe, InstanceKind), "R0"), "addr"),
          wsub(wsub(wref(s.name, s.tpe, InstanceKind), "R0"), "en"),
          EmptyExpression,
          seqMems(s.module).width.toInt)
        case s: DefMemory if s.readwriters.nonEmpty => (
          memPortField(s, s.readwriters.head, "rdata"),
          memPortField(s, s.readwriters.head, "addr"),
          memPortField(s, s.readwriters.head, "en"),
          memPortField(s, s.readwriters.head, "wmode"),
          bitWidth(s.dataType).toInt)
        case s: DefMemory => (
          memPortField(s, s.readers.head, "data"),
          memPortField(s, s.readers.head, "addr"),
          memPortField(s, s.readers.head, "en"),
          EmptyExpression,
          bitWidth(s.dataType).toInt)
      }
      val addrIo = wsub(chainIo(daisyIdx), "addrIo")
      val addrIn = wsub(addrIo, "in")
      val addrOut = wsub(addrIo, "out")
      def addrConnects(s: Statement): Statement = {
        s match {
          case Connect(info, loc, expr) => kind(loc) match {
            case MemKind | InstanceKind if weq(loc, en) =>
              repl(loc.serialize) = or(wsub(addrOut, "valid"), expr)
            case MemKind | InstanceKind if weq(loc, wmode) =>
              repl(loc.serialize) = and(not(wsub(addrOut, "valid")), expr)
            case MemKind | InstanceKind if weq(loc, addr) =>
              repl(loc.serialize) = Mux(wsub(addrOut, "valid"), wsub(addrOut, "bits"), expr, ut)
            case _ =>
          }
          case _ =>
        }
        s map addrConnects
      }
      def dataConnects =
        ((0 until daisyLen foldRight (Seq[Connect](), width - 1)){ case (i, (stmts, high)) =>
          val low = (high - daisyWidth + 1) max 0
          val input = bits(data, high, low)
          (stmts :+ (daisyWidth - (high - low + 1) match {
            case 0 =>
              // <daisy_chain>.io.dataIo.data[i] <- <memory>.data(high, low)
              Connect(NoInfo, widx(chainDataIo("data", daisyIdx), i), input)
            case margin =>
              val pad = UIntLiteral(0, IntWidth(margin))
              // <daisy_chain>.io.dataIo.data[i] <- cat(<memory>.data(high, low), pad)
              Connect(NoInfo, widx(chainDataIo("data", daisyIdx), i), cat(Seq(input, pad)))
          }), high - daisyWidth)
        })._1

      if (netlist.isEmpty) buildNetlist(netlist)(m.body)
      addrConnects(m.body)
      dataConnects ++ Seq(
        // <daisy_chain>.io.addr.in.bits <- <memory>.addr
        Connect(NoInfo, wsub(addrIn, "bits"), netlist(addr)),
        // <daisy_chain>.io.addr.in.valid <- <memory>.ren
        Connect(NoInfo, wsub(addrIn, "valid"), wmode match {
          case EmptyExpression => netlist(en)
          case _ => and(netlist(en), not(netlist(wmode)))
        })
      )
    }

    val chainElems = new Statements
    collect(chainType, chainElems)(m.body)
    chains(chainType)(m.name) = chainElems
    chainElems.nonEmpty match {
      case false => Nil
      case true => chainElems.zipWithIndex flatMap { case (sram, i) =>
        val (depth, width, seqRead) = sram match {
          case s: WDefInstance =>
            val seqMem = seqMems(s.module)
            (seqMem.depth.toInt, seqMem.width.toInt, true)
          case s: DefMemory =>
            (s.depth, bitWidth(s.dataType).toInt, false)
        }
        lazy val chain = new SRAMChain()(p alter Map(DataWidth -> width, SRAMSize -> depth, SeqRead -> seqRead))
        val instStmts = generateChain(() => chain, namespace, chainMods, i)
        val clocks = m.ports flatMap (p =>
          create_exps(wref(p.name, p.tpe)) filter (_.tpe ==  ClockType))
        val portConnects = Seq(
          // <daisy_chain>.clock <- clock
          Connect(NoInfo, wsub(chainRef(i), "clock"), clocks.head),
          // <daisy_chain>.reset <- daisyReset
          Connect(NoInfo, wsub(chainRef(i), "reset"), wref("daisyReset")),
          // <daisy_chain>.io.stall <- not(targetFire)
          Connect(NoInfo, wsub(chainIo(i), "stall"), not(wref("targetFire"))),
          // <daisy_chain>.io.restart <- <daisy_port>.restart
          Connect(NoInfo, wsub(chainIo(i), "restart"), daisyPort("restart")),
           // <daiy_port>.out <- <daisy_chain>.io.dataIo.out
          (if (i == 0) Connect(NoInfo, daisyPort("out"), chainDataIo("out", i))
           // <last_daisy_chain>.io.dataIo.in <- <daisy_chain>.io.dataIo.out
           else Connect(NoInfo, chainDataIo("in", i - 1), chainDataIo("out", i)))
        )
        hasChain += m.name
        instStmts ++ portConnects ++ daisyConnects(sram, i, chain.daisyLen, chain.daisyWidth)
      }
    }
  }

  private def insertChains(m: Module,
                           p: cde.Parameters,
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
      case ChainType.SRAM => insertSRAMChains(m, p, namespace, netlist, repl, chainMods, hasChain)
      case _              => insertRegChains(m, p, namespace, netlist, readers, chainMods, hasChain)
    }
    val chainNum = chainType match {
      case ChainType.SRAM => 1 max chains(chainType)(m.name).size
      case _              => 1
    }
    val invalids = childInsts(m.name) flatMap (c => Seq(
        IsInvalid(NoInfo, childDaisyPort(c)("in")),
        IsInvalid(NoInfo, childDaisyPort(c)("out"))))
    // Filter children who have daisy chains
    val childrenWithChains = childInsts(m.name) filter (x => hasChain(instToMod(x, m.name)))
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
                  clock: Expression,
                  stmts: Statements)
                  (s: Statement): Statement = s match {
    case s: WDefInstance if !(seqMems contains s.module) =>
      // Connect restart pin
      Block(Seq(s, Connect(NoInfo,
        childDaisyPort(s.name)("restart")(ChainType.SRAM),
        daisyPort("restart")(ChainType.SRAM))))
    case s: DefMemory => readers get s.name match {
      case None => s
      case Some(rs) =>
        val mem = s copy (readers = s.readers ++ rs)
        Block(mem +: (rs.zipWithIndex flatMap { case (r, i) =>
          val addr = UIntLiteral(i, IntWidth(chisel3.util.log2Up(s.depth)))
          Seq(Connect(NoInfo, memPortField(mem, r, "clk"), clock),
              Connect(NoInfo, memPortField(mem, r, "en"), one),
              Connect(NoInfo, memPortField(mem, r, "addr"), addr))
        }))
    }
    case s: Connect => kind(s.loc) match {
      case MemKind | InstanceKind => repl get s.loc.serialize match {
        case Some(expr) => 
          stmts += s copy (expr = expr)
          EmptyStmt
        case _ => s
      }
      case _ => s
    }
    case s => s map updateStmts(readers, repl, clock, stmts)
  }

  private def transform(namespace: Namespace,
                        daisyType: Type,
                        p: cde.Parameters,
                        chainMods: DefModules,
                        hasChain: Map[ChainType.Value, ChainModSet])
                        (m: DefModule) = m match {
    case m: ExtModule => m
    case m: Module =>
      val netlist = new Netlist
      val readers = new Readers
      val stmts = new Statements
      val repl = new Netlist
      val clocks = m.ports flatMap (p =>
        create_exps(wref(p.name, p.tpe)) filter (_.tpe ==  ClockType))
      val daisyPort = Port(NoInfo, "daisy", Output, daisyType)
      val daisyInvalid = IsInvalid(NoInfo, wref("daisy", daisyType))
      val chainStmts = (ChainType.values.toList map
        insertChains(m, p, namespace, netlist, readers, repl, chainMods, hasChain))
      val bodyx = updateStmts(readers, repl, clocks.head, stmts)(m.body)
      m copy (ports = m.ports :+ daisyPort,
              body = Block(Seq(daisyInvalid, bodyx) ++ chainStmts ++ stmts))
  }

  private def connectDaisyPort(stmts: Statements)(s: Statement): Statement = {
    s match {
      case s: WDefInstance if s.name == "target" =>
        stmts += Connect(NoInfo, wsub(wref("io"), "daisy"), wsub(wref("target"), "daisy"))
      case _ =>
    }
    s map connectDaisyPort(stmts)
  }

  def run(c: Circuit) = {
    val namespace = Namespace(c)
    val chainMods = new DefModules
    val hasChain = ChainType.values.toList map (_ -> new ChainModSet) toMap
    val modMap = (wrappers(c.modules) foldLeft Map[String, DefModule]()) { (map, m) =>
      val param = params(m.name)
      val daisyType = (m.ports filter (_.name == "io") flatMap (io => io.tpe match {
        case BundleType(fields) => fields filter (_.name == "daisy")
        case t => error(s"${io.info}: io should be a bundle type, but has type ${t.serialize}")
      })).head.tpe
      val stmts = new Statements
      val newMod = m map connectDaisyPort(stmts) match {
        case m: ExtModule => m
        case m: Module => m copy (body = Block(m.body +: stmts))
      }
      map ++ (
        (postorder(targets(m, c.modules), c.modules)(
         transform(namespace, daisyType, param, chainMods, hasChain)) :+ newMod)
        map (m => m.name -> m)
      )
    }
    c copy (modules = chainMods ++ (c.modules map (m => modMap getOrElse (m.name, m))))
  }
}
