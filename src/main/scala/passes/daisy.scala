package strober
package passes

import Utils._
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils._
import firrtl.passes.bitWidth
import firrtl.passes.MemPortUtils._
import firrtl.passes.PassException
import WrappedExpression.weq

private[passes] object AddDaisyChains extends firrtl.passes.Pass {
  def name = "[strober] Add Daisy Chains"
 
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
    val (modules, main) = (result.circuit.modules foldLeft
      (Seq[DefModule](), None: Option[String])){ case ((ms, main), m) =>
        val newMod = m match {
          // No copy method in DefModule
          case m: Module => m copy (name = namespace newName m.name)
        }
        ((ms :+ newMod), if (m.name == circuit.main) Some(newMod.name) else main)
    }
    chainMods ++= modules
    Seq(
      WDefInstance(NoInfo, chainRef(instIdx).name, main.get, ut),
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

  private def collect(chainType: ChainType.Value, chains: Statements)(s: Statement): Statement = {
    chainType match {
      // TODO: do bfs from inputs
      case ChainType.Regs => s match {
        case s: DefRegister =>
          chains += s
        case s: DefMemory if s.readLatency > 0 || s.depth < 16 =>
          chains += s
        case _ =>
      }
      case ChainType.SRAM => s match {
        case s: DefMemory if s.readLatency > 0 || s.depth > 16 =>
          chains += s
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
        netlist(s.loc.serialize) = s.expr
      case s: PartialConnect =>
        netlist(s.loc.serialize) = s.expr
      case s: DefNode =>
        netlist(s.name) = s.value
      case s =>
    }
    s map buildNetlist(netlist)
  }

  private def generateMemPipes(namespace: Namespace,
                               netlist: Netlist,
                               stmts: Statements)
                               (s: DefMemory): Seq[Expression] = {
    def insertBuf(buf: WRef, prev: Expression) {
      stmts += DefRegister(NoInfo, buf.name, buf.tpe, wref("clk"), wref("reset"), buf)
      stmts += Conditionally(NoInfo, wref("targetFire"), EmptyStmt, Connect(NoInfo, buf, prev))
    }
    val rpipes = (s.readers flatMap { reader =>
      val en = memPortField(s, reader, "en")
      val addr = memPortField(s, reader, "addr")
      val data = memPortField(s, reader, "data")
      (((0 until s.readLatency) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${reader}_en_buf_$i"
        val buf = wref(name, en.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(en.serialize) else exps.last)
        exps :+ buf
      }) ++ (((0 until s.readLatency) foldLeft Seq[Expression]()){(exps, i) =>
        // Capture all buffered addresses for RTL replays
        val name = namespace newName s"${s.name}_${reader}_addr_buf_$i"
        val buf = wref(name, addr.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(addr.serialize) else exps.last)
        exps :+ buf
      }) ++ (((0 until (s.readLatency-1)) foldLeft Seq[Expression](data)){(exps, i) =>
        // Capture all buffered data for gate-level replays
        val name = namespace newName s"${s.name}_${reader}_data_buf_$i"
        val buf = wref(name, data.tpe)
        insertBuf(buf, exps.last)
        exps :+ buf
      } flatMap (create_exps(_)))
    })
    val wpipes = (s.writers flatMap { writer =>
      val en = memPortField(s, writer, "en")
      val mask = memPortField(s, writer, "mask")
      val addr = memPortField(s, writer, "addr")
      val data = memPortField(s, writer, "data")
      (((0 until (s.writeLatency-1)) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${writer}_en_buf_$i"
        val buf = wref(name, en.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(en.serialize) else exps.last)
        exps :+ buf
      }) ++ (((0 until (s.writeLatency-1)) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${writer}_mask_buf_$i"
        val buf = wref(name, mask.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(mask.serialize) else exps.last)
        exps :+ buf
      }) ++ (((0 until (s.writeLatency-1)) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${writer}_addr_buf_$i"
        val buf = wref(name, addr.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(addr.serialize) else exps.last)
        insertBuf(buf, exps.last)
        exps :+ buf
      }) ++ (((0 until (s.writeLatency-1)) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${writer}_data_buf_$i"
        val buf = wref(name, data.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(data.serialize) else exps.last)
        exps :+ buf
      } flatMap (create_exps(_)))
    })
    val rwpipes = (s.readwriters flatMap {readwriter =>
      val en = memPortField(s, readwriter, "en")
      val addr = memPortField(s, readwriter, "addr")
      val rdata = memPortField(s, readwriter, "rdata")
      val wmode = memPortField(s, readwriter, "wmode")
      val wmask = memPortField(s, readwriter, "wmask")
      val wdata = memPortField(s, readwriter, "wdata")
      (((0 until s.readLatency) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${readwriter}_en_buf_$i"
        val buf = wref(name, en.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(en.serialize) else exps.last)
        exps :+ buf
      }) ++ (((0 until s.readLatency) foldLeft Seq[Expression]()){(exps, i) =>
        // Capture all buffered addresses for RTL replays
        val name = namespace newName s"${s.name}_${readwriter}_addr_buf_$i"
        val buf = wref(name, addr.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(addr.serialize) else exps.last)
        exps :+ buf
      }) ++ (((0 until (s.readLatency-1)) foldLeft Seq[Expression](rdata)){(exps, i) =>
        // Capture all buffered data for gate-level replays
        val name = namespace newName s"${s.name}_${readwriter}_rdata_buf_$i"
        val buf = wref(name, rdata.tpe)
        insertBuf(buf, exps.last)
        exps :+ buf
      } flatMap (create_exps(_))) ++ 
      (((0 until (s.writeLatency-1)) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${readwriter}_wmode_buf_$i"
        val buf = wref(name, wmode.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(wmode.serialize) else exps.last)
        exps :+ buf
      }) ++ (((0 until (s.writeLatency-1)) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${readwriter}_wmask_buf_$i"
        val buf = wref(name, wmask.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(wmask.serialize) else exps.last)
        exps :+ buf
      }) ++ (((0 until (s.writeLatency-1)) foldLeft Seq[Expression]()){(exps, i) =>
        val name = namespace newName s"${s.name}_${readwriter}_wdata_buf_$i"
        val buf = wref(name, wdata.tpe)
        insertBuf(buf, if (exps.isEmpty) netlist(wdata.serialize) else exps.last)
        exps :+ buf
      } flatMap (create_exps(_)))
    })
    rpipes ++ wpipes ++ rwpipes
  }

  private def insertRegChains(m: Module,
                              p: cde.Parameters,
                              namespace: Namespace,
                              readers: Readers,
                              chainMods: DefModules,
                              hasChain: ChainModSet)
                              (implicit chainType: ChainType.Value) = {
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
        lazy val chain = new RegChain()(p alter Map(DataWidth -> sumWidths(m.body).toInt))
        val instStmts = generateChain(() => chain, namespace, chainMods)
        val portConnects = Seq(
          // <daisy_chain>.clk <- clk
          Connect(NoInfo, wsub(chainRef(), "clk"), wref("clk")),
          // <daisy_chain>.reset <- daisyReset
          Connect(NoInfo, wsub(chainRef(), "reset"), wref("daisyReset")),
          // <daisy_chain>.io.stall <- not(targetFire)
          Connect(NoInfo, wsub(chainIo(), "stall"), not(wref("targetFire"))),
          // <daiy_port>.out <- <daisy_chain>.io.dataIo.out
          Connect(NoInfo, daisyPort("out"), chainDataIo("out"))
        )
        hasChain += m.name
        chainLen(chainType) += chain.daisyLen
        chainLoop(chainType) = 1
        val stmts = new Statements
        val netlist = new Netlist
        val regs = chains(chainType)(m.name) flatMap {
          case s: DefMemory if s.readLatency > 0 =>
            if (netlist.isEmpty) buildNetlist(netlist)(m.body)
            generateMemPipes(Namespace(m), netlist, stmts)(s)
          case s: DefMemory =>
            val rs = (0 until s.depth) map (i => s"scan_$i")
            val mem = s copy (readers = s.readers ++ rs)
            val exps = rs map (r => create_exps(memPortField(mem, r, "data")))
            readers(s.name) = rs
            ((0 until exps.head.size) foldLeft Seq[Expression]())(
              (res, i) => res ++ (exps map (_(i))))
          case s: DefRegister => create_exps(s.name, s.tpe)
          case s => Nil
        }
        stmts ++ instStmts ++ portConnects ++ daisyConnects(regs, chain.daisyLen, chain.daisyWidth)
    }
  }

  private def insertSRAMChains(m: Module,
                               p: cde.Parameters,
                               namespace: Namespace,
                               repl: Netlist,
                               chainMods: DefModules,
                               hasChain: ChainModSet)
                               (implicit chainType: ChainType.Value) = {
    def daisyConnects(sram: DefMemory, daisyIdx: Int, daisyLen: Int, daisyWidth: Int) = {
      val data = if (!sram.readwriters.isEmpty)
        memPortField(sram, sram.readwriters.head, "rdata") else
        memPortField(sram, sram.readers.head, "data")
      val addr = if (!sram.readwriters.isEmpty)
        memPortField(sram, sram.readwriters.head, "addr") else
        memPortField(sram, sram.readers.head, "addr")
      val en = if (!sram.readwriters.isEmpty)
        memPortField(sram, sram.readwriters.head, "en") else
        memPortField(sram, sram.readers.head, "en")
      val wmode = if (!sram.readwriters.isEmpty)
        memPortField(sram, sram.readwriters.head, "wmode") else
        EmptyExpression
      val width = bitWidth(sram.dataType).toInt
      def addrIo = wsub(wsub(chainIo(daisyIdx), "addrIo"), "out")
      def addrConnects(s: Statement): Statement = {
        s match {
          case Connect(info, loc, expr) => kind(loc) match {
            case MemKind if weq(loc, en) =>
              repl(loc.serialize) = or(wsub(addrIo, "valid"), expr)
            case MemKind if weq(loc, wmode) =>
              repl(loc.serialize) = and(not(wsub(addrIo, "valid")), expr)
            case MemKind if weq(loc, addr) =>
              repl(loc.serialize) = Mux(wsub(addrIo, "valid"), wsub(addrIo, "bits"), expr, ut)
            case _ =>
          }
          case _ =>
        }
        s map addrConnects
      }
      def dataConnects = ((0 until daisyLen foldRight (Seq[Connect](), width - 1)){
        case (i, (stmts, high)) =>
          val low = (high - daisyWidth + 1) max 0
          val input = bits(data, high, low)
          (stmts :+ (daisyWidth - (high - low + 1) match {
            case 0 =>
              // "<daisy_chain>.io.dataIo.data[i] <- <memory>.data(high, low)"
              Connect(NoInfo, widx(chainDataIo("data", daisyIdx), i), input)
            case margin =>
              val pad = UIntLiteral(0, IntWidth(margin))
              // "<daisy_chain>.io.dataIo.data[i] <- cat(<memory>.data(high, low), pad)"
              Connect(NoInfo, widx(chainDataIo("data", daisyIdx), i), cat(Seq(input, pad)))
          }), high - daisyWidth)
      })._1

      addrConnects(m.body)
      dataConnects
    }

    val chainElems = new Statements
    collect(chainType, chainElems)(m.body)
    chains(chainType)(m.name) = chainElems
    chainElems.nonEmpty match {
      case false => Nil
      case true => chainElems.zipWithIndex flatMap { case (sram: DefMemory, i) =>
        lazy val chain = new SRAMChain()(p alter Map(
          DataWidth -> bitWidth(sram.dataType).toInt, SRAMSize -> sram.depth))
        val instStmts = generateChain(() => chain, namespace, chainMods, i)
        val portConnects = Seq(
          // <daisy_chain>.clk <- clk
          Connect(NoInfo, wsub(chainRef(i), "clk"), wref("clk")),
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
        chainLen(chainType) += chain.daisyLen
        chainLoop(chainType) = chainLoop(chainType) max sram.depth
        instStmts ++ portConnects ++ daisyConnects(sram, i, chain.daisyLen, chain.daisyWidth)
      }
    }
  }

  private def insertChains(m: Module,
                           p: cde.Parameters,
                           namespace: Namespace,
                           readers: Readers,
                           repl: Netlist,
                           chainMods: DefModules,
                           hasChainMap: Map[ChainType.Value, ChainModSet])
                           (t: ChainType.Value) = {
    implicit val chainType = t
    val hasChain = hasChainMap(chainType)
    val chainStmts = chainType match {
      case ChainType.SRAM => insertSRAMChains(m, p, namespace, repl, chainMods, hasChain)
      case _              => insertRegChains(m, p, namespace, readers, chainMods, hasChain)
    }
    val chainNum = chainType match {
      case ChainType.SRAM => 1 max chains(chainType)(m.name).size
      case _              => 1
    }
    // Filter children who have daisy chains
    (childInsts(m.name) filter hasChain foldLeft (None: Option[String], Seq[Connect]())){
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
      case (None, stmts) if !hasChain(m.name) =>
        Block(chainStmts ++ stmts)
      case (None, stmts) =>
        // <daisy_chain>.io.dataIo.in <- <daisy_port>.in
        Block(chainStmts ++ (stmts :+ Connect(NoInfo, chainDataIo("in", chainNum-1), daisyPort("in"))))
      case (Some(p), stmts) =>
        // <prev_child>.<daisy_port>.in <- <daisy_port>.in
        Block(chainStmts ++ (stmts :+ Connect(NoInfo, childDaisyPort(p)("in"), daisyPort("in"))))
    }
  }

  def updateStmts(readers: Readers,
                  repl: Netlist,
                  stmts: Statements)
                  (s: Statement): Statement = s match {
    case s: DefMemory => readers get s.name match {
      case None => s
      case Some(rs) =>
        val mem = s copy (readers = s.readers ++ rs)
        Block(mem +: (rs.zipWithIndex flatMap { case (r, i) =>
          val addr = UIntLiteral(i, IntWidth(chisel3.util.log2Up(s.depth)))
          Seq(Connect(NoInfo, memPortField(mem, r, "clk"), wref("clk")),
              Connect(NoInfo, memPortField(mem, r, "en"), one),
              Connect(NoInfo, memPortField(mem, r, "addr"), addr))
        }))
    }
    case s: Connect => kind(s.loc) match {
      case MemKind => repl get s.loc.serialize match {
        case Some(expr) => 
          stmts += s copy (expr = expr)
          EmptyStmt
        case _ => s
      }
      case _ => s
    }
    case s => s map updateStmts(readers, repl, stmts)
  }

  private def transform(namespace: Namespace,
                        daisyType: Type,
                        p: cde.Parameters,
                        chainMods: DefModules,
                        hasChain: Map[ChainType.Value, ChainModSet])
                        (m: DefModule) = m match {
    case m: ExtModule => m
    case m: Module =>
      val readers = new Readers
      val stmts = new Statements
      val repl = new Netlist
      val daisyPort = Port(NoInfo, "daisy", Output, daisyType)
      val daisyInvalid = IsInvalid(NoInfo, wref("daisy", daisyType))
      val chainStmts = (ChainType.values.toList map
        insertChains(m, p, namespace, readers, repl, chainMods, hasChain))
      val bodyx = updateStmts(readers, repl, stmts)(m.body)
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
        case t => throw new PassException(
          s"${io.info}: io should be a bundle type, but has type ${t.serialize}")
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
