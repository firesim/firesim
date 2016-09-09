package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.bitWidth
import firrtl.passes.MemPortUtils._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import scala.util.DynamicVariable

private class DaisyChainContext {
  val chainModules = ArrayBuffer[DefModule]()
  val hasChain = Map(
    ChainType.Trace -> HashSet[String](),
    ChainType.Regs  -> HashSet[String](),
    ChainType.SRAM  -> HashSet[String](),
    ChainType.Cntr  -> HashSet[String]()
  )
  val readers = HashMap[String, HashMap[String, Seq[String]]]()
  val enables = HashMap[String, HashMap[WrappedExpression, Expression]]()
  val wmodes = HashMap[String, HashMap[WrappedExpression, Expression]]()
  val addrs = HashMap[String, HashMap[WrappedExpression, Expression]]()
  var regIndex = 0
  var sramIndex = 0
}

private[passes] object AddDaisyChains extends firrtl.passes.Pass {
  def name = "[strober] Add Daisy Chains"
  import Utils._
  import firrtl.Utils._
  import firrtl.WrappedExpression._
  import firrtl.passes.PassException
  private val contextVar = new DynamicVariable[Option[DaisyChainContext]](None)
  private def context = contextVar.value.getOrElse (new DaisyChainContext)
  private def chainModules = context.chainModules
  private def hasChain = context.hasChain
  private def readers = context.readers
  private def enables = context.enables
  private def wmodes = context.wmodes
  private def addrs = context.addrs
  private def regIndex = context.regIndex
  private def sramIndex = context.sramIndex
 
  private class ExtractChainModules(index: Int) extends Transform with SimpleRun {
    def rename(s: Statement): Statement = s map rename match {
      case WDefInstance(info, name, module, tpe) =>
        WDefInstance(info, name, s"${module}_${index}", tpe)
      case s => s
    }

    def renameModule(m: DefModule) = m match {
      case Module(info, name, ports, body) =>
        val m = Module(info, s"${name}_${index}", ports, body map rename)
        chainModules += m
        m
      case ExtModule(info, name, ports) =>
        val m = ExtModule(info, "s${name}_${index}", ports)
        chainModules += m
        m
    }

    def execute(c: Circuit, annotationMap: Annotations.AnnotationMap) =
      run(Circuit(c.info, c.modules map renameModule, "s${c.main}_${index}"), Nil)
  }

  private class ChainCompiler(index: Int) extends Compiler {
    def transforms(writer: java.io.Writer) = Seq(
      new Chisel3ToHighFirrtl,
      new IRToWorkingIR,
      new ResolveAndCheck,
      new HighFirrtlToMiddleFirrtl,
      new ExtractChainModules(index),
      new EmitFirrtl(writer) // debugging
    )
  }
 
  private def chainRef(i: Int = 0)(implicit chainType: ChainType.Value) =
    chainType match {
      case ChainType.Trace => wref(s"trace_chain_$i")
      case ChainType.Regs  => wref(s"reg_chain_$i")
      case ChainType.SRAM  => wref(s"sram_chain_$i")
      case ChainType.Cntr  => wref(s"cntr_chain_$i")
    }

  private def chainIo(i: Int = 0)(implicit chainType: ChainType.Value) = wsub(chainRef(i), "io")

  private def chainDataIo(pin: String, i: Int = 0)(implicit chainType: ChainType.Value) =
    wsub(wsub(chainIo(i), "dataIo"), pin)

  private def daisyPort(pin: String, idx: Int = 0)(
      implicit chainType: ChainType.Value) = chainType match {
    case ChainType.Trace => wsub(widx(wsub(wref("daisy"), "trace"), idx), pin)
    case ChainType.Regs  => wsub(widx(wsub(wref("daisy"), "regs"), idx), pin)
    case ChainType.SRAM  => wsub(widx(wsub(wref("daisy"), "sram"), idx), pin)
    case ChainType.Cntr  => wsub(widx(wsub(wref("daisy"), "cntr"), idx), pin)
  }

  private def childDaisyPort(child: String)(pin: String, idx: Int = 0)(
      implicit chainType: ChainType.Value) = chainType match {
    case ChainType.Trace => wsub(widx(wsub(wsub(wref(child), "daisy"), "trace"), idx), pin)
    case ChainType.Regs  => wsub(widx(wsub(wsub(wref(child), "daisy"), "regs"), idx), pin)
    case ChainType.SRAM  => wsub(widx(wsub(wsub(wref(child), "daisy"), "sram"), idx), pin)
    case ChainType.Cntr  => wsub(widx(wsub(wsub(wref(child), "daisy"), "cntr"), idx), pin)
  }

  private def collect(s: Statement)(implicit chainType: ChainType.Value): Seq[Statement] =
    chainType match {
      // TODO: do bfs from inputs
      case ChainType.Regs => s match {
        case s: DefRegister => Seq(s)
        case s: DefMemory if s.readLatency > 0 || s.depth < 16 => Seq(s)
        case s: Block => s.stmts flatMap collect
        case s => Nil
      }
      case ChainType.SRAM => s match {
        case s: DefMemory if s.readLatency > 0 || s.depth > 16 => Seq(s)
        case s: Block => s.stmts flatMap collect
        case s => Nil
      }
      case ChainType.Trace => Nil
      case ChainType.Cntr => Nil
    }

  private def insertRegChains(m: Module, p: cde.Parameters)(implicit chainType: ChainType.Value) = {
    def daisyConnects(regs: Seq[Expression], daisyLen: Int, daisyWidth: Int) = {
      (((0 until daisyLen) foldRight (Seq[Connect](), 0, 0)){case (i, (cons, index, offset)) =>
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
        (cons :+ Connect(NoInfo, widx(chainDataIo("data"), i), cat(wires)), idx, off)
      })._1
    }

    chains(chainType)(m.name) = collect(m.body)
    if (chains(chainType)(m.name).isEmpty) EmptyStmt else {
      lazy val chain = new RegChain()(p alter Map(DataWidth -> sumWidths(m.body).toInt))
      val circuit = Parser parse (chisel3.Driver.emit(() => chain))
      val annotation = new Annotations.AnnotationMap(Nil)
      val output = new java.io.StringWriter
      (new ChainCompiler(regIndex)) compile (circuit, annotation, output)

      val inst = WDefInstance(NoInfo, chainRef().name, s"RegChain_${regIndex}", ut)
      val invalid = IsInvalid(NoInfo, chainRef())
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
      context.regIndex += 1
      hasChain(chainType) += m.name
      chainLen(chainType) += chain.daisyLen
      chainLoop(chainType) = 1
      val namespace = Namespace(m)
      val stmts = ArrayBuffer[Statement]()
      val netlist = HashMap[String, Expression]()
      def buildNetlist(s: Statement): Statement = {
        s match {
          case s: Connect =>
            netlist(s.loc.serialize) = s.expr
          case s: PartialConnect =>
            netlist(s.loc.serialize) = s.expr
          case s: DefNode =>
            netlist(s.name) = s.value
          case s =>
        }
        s map buildNetlist
      }
      val regs = chains(chainType)(m.name) flatMap {
        case s: DefMemory if s.readLatency > 0 =>
          if (netlist.isEmpty) buildNetlist(m.body)
          // val mem = wref(s.name, memToBundle(s))
          def insertBuf(buf: WRef, prev: Expression) {
            stmts += DefRegister(NoInfo, buf.name, buf.tpe, wref("clk"), wref("reset"), buf)
            stmts += Conditionally(NoInfo, wref("targetFire"), EmptyStmt, Connect(NoInfo, buf, prev))
          }
          (s.readers flatMap {reader =>
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
          }) ++ (s.writers flatMap {writer =>
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
          }) ++ (s.readwriters flatMap {readwriter =>
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
        case s: DefMemory =>
          readers(m.name)(s.name) = (0 until s.depth) map (i => s"scan_$i")
          val exps = readers(m.name)(s.name).zipWithIndex map {case (reader, i) =>
            create_exps(wsub(wsub(wref(s.name), reader), "data", s.dataType))}
          ((0 until exps.head.size) foldLeft Seq[Expression]())((res, i) => res ++ (exps map (_(i))))
        case s: DefRegister => create_exps(s.name, s.tpe)
        case s => Nil
      }
      Block(stmts ++ Seq(inst, invalid) ++ portConnects ++
            daisyConnects(regs, chain.daisyLen, chain.daisyWidth))
    }
  }

  private def insertSRAMChains(m: Module, p: cde.Parameters)(implicit chainType: ChainType.Value) = {
    def daisyConnects(sram: DefMemory, daisyIdx: Int, daisyLen: Int, daisyWidth: Int) = {
      val data = if (!sram.readwriters.isEmpty)
        wsub(wsub(wref(sram.name), sram.readwriters.head), "rdata") else
        wsub(wsub(wref(sram.name), sram.readers.head), "data")
      val addr = if (!sram.readwriters.isEmpty)
        wsub(wsub(wref(sram.name), sram.readwriters.head), "addr") else
        wsub(wsub(wref(sram.name), sram.readers.head), "addr")
      val en = if (!sram.readwriters.isEmpty)
        wsub(wsub(wref(sram.name), sram.readwriters.head), "en") else
        wsub(wsub(wref(sram.name), sram.readers.head), "en")
      val wmode = if (!sram.readwriters.isEmpty)
        wsub(wsub(wref(sram.name), sram.readwriters.head), "wmode") else
        EmptyExpression
      val width = bitWidth(sram.dataType).toInt
      def addrIo = wsub(wsub(chainIo(daisyIdx), "addrIo"), "out")
      def addrConnects(s: Statement): Statement = {
        s match {
          case Connect(info, loc, expr) if weq(loc, en) =>
            enables(m.name)(we(loc)) = or(wsub(addrIo, "valid"), expr)
          case Connect(info, loc, expr) if weq(loc, wmode) =>
            wmodes(m.name)(we(wmode)) = and(not(wsub(addrIo, "valid")), expr)
          case Connect(info, loc, expr) if weq(loc, addr) =>
            addrs(m.name)(we(addr)) = Mux(wsub(addrIo, "valid"), wsub(addrIo, "bits"), expr, ut)
          case _ =>
        }
        s map addrConnects
      }
      def dataConnects = (((0 until daisyLen) foldRight (Seq[Connect](), (width-1))){case (i, (cons, high)) =>
        val low = math.max(high-daisyWidth+1, 0)
        val margin = daisyWidth-(high-low+1)
        val input = bits(data, high, low)
        (cons :+ ((daisyWidth-(high-low+1)) match {
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

    chains(chainType)(m.name) = collect(m.body)
    if (chains(chainType)(m.name).isEmpty) EmptyStmt else Block(
      chains(chainType)(m.name).zipWithIndex flatMap {case (sram: DefMemory, i) =>
        lazy val chain = new SRAMChain()(p alter Map(
          DataWidth -> bitWidth(sram.dataType).toInt, SRAMSize -> sram.depth))
        val circuit = Parser parse (chisel3.Driver.emit(() => chain))
        val annotation = new Annotations.AnnotationMap(Nil)
        val output = new java.io.StringWriter
        (new ChainCompiler(sramIndex)) compile (circuit, annotation, output)

        val inst = WDefInstance(NoInfo, chainRef(i).name, s"SRAMChain_${sramIndex}", ut)
        val invalid = IsInvalid(NoInfo, chainRef(i))
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
        context.sramIndex += 1
        hasChain(chainType) += m.name
        chainLen(chainType) += chain.daisyLen
        chainLoop(chainType) = math.max(chainLoop(chainType), sram.depth)
        Seq(inst, invalid) ++ portConnects ++ daisyConnects(sram, i, chain.daisyLen, chain.daisyWidth)
      }
    )
  }

  private def insertChains(m: Module, p: cde.Parameters)(t: ChainType.Value) = {
    implicit val chainType = t
    val chain = chainType match {
      case ChainType.SRAM => insertSRAMChains(m, p)
      case _              => insertRegChains(m, p)
    }
    val chainNum = chainType match {
      case ChainType.SRAM => chains(chainType)(m.name).size
      case _ => 1
    }
    // Filter children who have daisy chains
    (childInsts(m.name) filter (hasChain(chainType)(_)) foldLeft (None: Option[String], Seq[Connect]())){
      case ((None, cons), child) if !hasChain(chainType)(m.name) =>
        // <daisy_port>.out <- <child>.<daisy_port>.out
        (Some(child), cons :+ Connect(NoInfo, daisyPort("out"), childDaisyPort(child)("out")))
      case ((None, cons), child) =>
        // <daisy_chain>.io.dataIo.in <- <child>.<daisy_port>.out
        (Some(child), cons :+ Connect(NoInfo, chainDataIo("in", chainNum-1), childDaisyPort(child)("out")))
      case ((Some(p), cons), child) =>
        // <prev_child>.<daisy_port>.io.in <- <child>.<daisy_port>.out
        (Some(child), cons :+ Connect(NoInfo, childDaisyPort(p)("in"), childDaisyPort(child)("out")))
    } match {
      case (None, cons) if !hasChain(chainType)(m.name) => Block(chain +: cons)
      case (None, cons) =>
        // <daisy_chain>.io.dataIo.in <- <daisy_port>.in
        Block(chain +: (cons :+ Connect(NoInfo, chainDataIo("in", chainNum-1), daisyPort("in"))))
      case (Some(p), cons) =>
        // <prev_child>.<daisy_port>.in <- <daisy_port>.in
        Block(chain +: (cons :+ Connect(NoInfo, childDaisyPort(p)("in"), daisyPort("in"))))
    }
  }

  private def transform(daisyType: Type, p: cde.Parameters)(m: DefModule) = m match {
    case m: ExtModule => m
    case m: Module =>
      readers(m.name) = HashMap[String, Seq[String]]()
      enables(m.name) = HashMap[WrappedExpression, Expression]()
      wmodes(m.name) = HashMap[WrappedExpression, Expression]()
      addrs(m.name) = HashMap[WrappedExpression, Expression]()
      val daisyPort = Port(NoInfo, "daisy", Output, daisyType)
      val daisyInvalid = IsInvalid(NoInfo, wref("daisy"))
      val chains = ChainType.values.toList map insertChains(m, p)
      val updateConnects = ArrayBuffer[Connect]()
      def updateMemories(s: Statement): Statement =
        s map updateMemories match {
          case s: DefMemory if readers(m.name) contains s.name => Block(
            DefMemory(s.info, s.name, s.dataType, s.depth, s.writeLatency, s.readLatency,
              s.readers ++ readers(m.name)(s.name), s.writers, s.readwriters, s.readUnderWrite) +:
            (readers(m.name)(s.name).zipWithIndex flatMap {case (reader, i) =>
              val one = UIntLiteral(1, IntWidth(1))
              val addr = UIntLiteral(i, IntWidth(chisel3.util.log2Up(s.depth)))
              Seq(Connect(NoInfo, wsub(wsub(wref(s.name), reader), "clk"), wref("clk")),
                  Connect(NoInfo, wsub(wsub(wref(s.name), reader), "en"), one),
                  Connect(NoInfo, wsub(wsub(wref(s.name), reader), "addr"), addr))
            })
          )
          case Connect(info, loc, _) if addrs(m.name) contains loc =>
            updateConnects += Connect(info, loc, addrs(m.name)(loc))
            EmptyStmt
          case Connect(info, loc, _) if enables(m.name) contains loc =>
            updateConnects += Connect(info, loc, enables(m.name)(loc))
            EmptyStmt
          case Connect(info, loc, _) if wmodes(m.name) contains loc =>
            updateConnects += Connect(info, loc, wmodes(m.name)(loc))
            EmptyStmt
          case s => s
        }
      Module(m.info, m.name, m.ports :+ daisyPort,
        Block(Seq(daisyInvalid, m.body map updateMemories) ++ chains ++ updateConnects))
  }

  def run(c: Circuit) = (contextVar withValue Some(new DaisyChainContext)){
    val transformedModules = (wrappers(c.modules) flatMap {
      case m: Module =>
        val param = params(m.name)
        val daisyType = (m.ports filter (_.name == "io") flatMap (io => io.tpe match {
          case BundleType(fields) => fields filter (_.name == "daisy")
          case t => throw new PassException(
            s"${io.info}: io should be a bundle type, but has type ${t.serialize}")
        })).head.tpe
        def connectDaisyPort(s: Statement): Seq[Connect] = s match {
          case s: WDefInstance if s.name == "target" => Seq(
            Connect(NoInfo, wsub(wref("io"), "daisy"), wsub(wref("target"), "daisy")))
          case s: Block => s.stmts flatMap connectDaisyPort
          case _ => Nil
        }
        val body = Block(m.body +: connectDaisyPort(m.body))
        (postorder(targets(m, c.modules), c.modules)(transform(daisyType, param)) map (
          x => x.name -> x)) :+ (m.name -> Module(m.info, m.name, m.ports, body))
      case m: ExtModule => Seq(m.name -> m)
    }).toMap
    Circuit(c.info, chainModules.toSeq ++ (c.modules map (m => transformedModules getOrElse (m.name, m))), c.main)
  }
}
