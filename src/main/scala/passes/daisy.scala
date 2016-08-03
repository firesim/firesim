package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}

private[strober] object AddDaisyChains extends firrtl.passes.Pass {
  def name = "[strober] Add Daisy Chains"
  import Utils._
  import firrtl.Utils._
  import firrtl.passes.PassException

  val chainModules = ArrayBuffer[DefModule]()
  val hasChain = Map(
    ChainType.Trace -> HashSet[String](),
    ChainType.Regs  -> HashSet[String](),
    ChainType.SRAM  -> HashSet[String](),
    ChainType.Cntr  -> HashSet[String]()
  )
  var regIndex = 0
  var sramIndex = 0
  val readers = HashMap[String, HashMap[String, Seq[String]]]()

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
 
  private def chainRef(implicit chainType: ChainType.Value) =
    chainType match {
      case ChainType.Trace => wref("trace_chain")
      case ChainType.Regs  => wref("reg_chain")
      case ChainType.SRAM  => wref("sram_chain")
      case ChainType.Cntr  => wref("cntr_chain")
    }

  private def chainIo(implicit chainType: ChainType.Value) = wsub(chainRef, "io")

  private def chainDataIo(pin: String)(implicit chainType: ChainType.Value) =
    wsub(wsub(chainIo, "dataIo"), pin)

  private def daisyPort(pin: String, idx: Int = 0)(implicit chainType: ChainType.Value) =
    chainType match {
      case ChainType.Trace => wsub(widx(wsub(wref("daisy"), "trace"), idx), pin)
      case ChainType.Regs  => wsub(widx(wsub(wref("daisy"), "regs"), idx), pin)
      case ChainType.SRAM  => wsub(widx(wsub(wref("daisy"), "sram"), idx), pin)
      case ChainType.Cntr  => wsub(widx(wsub(wref("daisy"), "cntr"), idx), pin)
    }

  private def childDaisyPort(child: String)(pin: String, idx: Int = 0)(implicit chainType: ChainType.Value) =
    chainType match {
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
        case s: DefMemory if s.readLatency == 0 && s.depth <= 16 => Seq(s)
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
    def daisyConnect(regs: Seq[Expression], daisyLen: Int, daisyWidth: Int) = {
      (((0 until daisyLen) foldRight (Seq[Connect](), 0, 0)){case (i, (cons, index, offset)) =>
        def loop(total: Int, index: Int, offset: Int, wires: Seq[Expression]): (Int, Int, Seq[Expression]) = {
          (daisyWidth - total) match {
            case 0 => (index, offset, wires)
            case margin if index < regs.size =>
              val reg = regs(index)
              val width = sumWidths(reg.tpe).toInt - offset
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
      val circuit = Parser parse (chisel3.Driver.emit(() => chain) split "\n")
      val annotation = new Annotations.AnnotationMap(Nil)
      val output = new java.io.StringWriter
      (new ChainCompiler(regIndex)).compile(circuit, annotation, output)

      val inst = WDefInstance(NoInfo, chainRef.name, s"RegChain_${regIndex}", ut)
      val invalid = IsInvalid(NoInfo, chainRef)
      val connects = Seq(
        // <daisy_chain>.clk <- clk
        Connect(NoInfo, wsub(chainRef, "clk"), wref("clk")),
        // <daisy_chain>.reset <- daisyReset
        Connect(NoInfo, wsub(chainRef, "reset"), wref("daisyReset")),
        // <daisy_chain>.io.stall <- not(targetFire)
        Connect(NoInfo, wsub(chainIo, "stall"), not(wref("targetFire"))),
        // <daiy_port>.out <- <daisy_chain>.io.dataIo.out
        Connect(NoInfo, daisyPort("out"), chainDataIo("out"))
      )
      regIndex += 1
      hasChain(chainType) += m.name
      chainLen(chainType) += chain.daisyLen
      chainLoop(chainType) = 1
      val regs = chains(chainType)(m.name) flatMap {
        case s: DefMemory =>
          readers(m.name)(s.name) = (0 until s.depth) map (i => s"scan_$i")
          val exps = readers(m.name)(s.name).zipWithIndex map {case (reader, i) =>
            create_exps(wsub(wsub(wref(s.name), reader), "data", s.dataType))
          }
          ((0 until exps.head.size) foldLeft Seq[Expression]())((res, i) => res ++ (exps map (_(i))))
        case s: DefRegister => create_exps(s.name, s.tpe)
        case s => Nil
      }
      Block(Seq(inst, invalid) ++ connects ++ daisyConnect(regs, chain.daisyLen, chain.daisyWidth))
    }
  }

  private def insertSRAMChains(m: Module) = {
    EmptyStmt
  }

  private def insertChains(m: Module, p: cde.Parameters)(t: ChainType.Value) = {
    implicit val chainType = t
    val chain = chainType match {
      case ChainType.SRAM => insertSRAMChains(m)
      case _              => insertRegChains(m, p)(chainType)
    }
    // Filter children who have daisy chains
    (childInsts(m.name) filter (hasChain(chainType)(_)) foldLeft (None: Option[String], Seq[Connect]())){
      case ((None, cons), child) if !hasChain(chainType)(m.name) =>
        // <daisy_port>.out <- <child>.<daisy_port>.out
        (Some(child), cons :+ Connect(NoInfo, daisyPort("out"), childDaisyPort(child)("out")))
      case ((None, cons), child) =>
        // <daisy_chain>.io.dataIo.in <- <child>.<daisy_port>.out
        (Some(child), cons :+ Connect(NoInfo, chainDataIo("in"), childDaisyPort(child)("out")))
      case ((Some(p), cons), child) =>
        // <prev_child>.<daisy_port>.io.in <- <child>.<daisy_port>.out
        (Some(child), cons :+ Connect(NoInfo, childDaisyPort(p)("in"), childDaisyPort(child)("out")))
    } match {
      case (None, cons) if !hasChain(chainType)(m.name) => Block(chain +: cons)
      case (None, cons) =>
        // <daisy_chain>.io.dataIo.in <- <daisy_port>.in
        Block(chain +: (cons :+ Connect(NoInfo, chainDataIo("in"), daisyPort("in"))))
      case (Some(p), cons) =>
        // <prev_child>.<daisy_port>.in <- <daisy_port>.in
        Block(chain +: (cons :+ Connect(NoInfo, childDaisyPort(p)("in"), daisyPort("in"))))
    }
  }

  private def transform(daisyType: Type, p: cde.Parameters)(m: DefModule) = m match {
    case m: ExtModule => m
    case m: Module =>
      readers(m.name) = HashMap[String, Seq[String]]()
      val daisyPort = Port(NoInfo, "daisy", Output, daisyType)
      val daisyInvalid = IsInvalid(NoInfo, wref("daisy"))
      val chains = ChainType.values.toList map insertChains(m, p)
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
          case s => s
        }
      Module(m.info, m.name, m.ports :+ daisyPort,
        Block(Seq(daisyInvalid, m.body map updateMemories) ++ chains))
  }

  def run(c: Circuit) = {
    chainModules.clear
    ChainType.values foreach (hasChain(_).clear)
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
