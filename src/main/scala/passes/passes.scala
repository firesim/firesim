package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.bitWidth
import firrtl.Utils.{sub_type, field_type, create_exps}
import scala.collection.mutable.{ArrayBuffer, Stack, HashSet, LinkedHashSet}
import java.io.{File, FileWriter, Writer}

private[passes] object Utils {
  val ut = UnknownType
  val uw = UnknownWidth
  val ug = UNKNOWNGENDER
  
  def wref(s: String, t: Type = ut, k: Kind = ExpKind) = WRef(s, t, k, ug)
  def wsub(e: Expression, s: String) = WSubField(e, s, field_type(e.tpe, s), ug)
  def widx(e: Expression, i: Int) = WSubIndex(e, i, sub_type(e.tpe), ug)
  def not(e: Expression) = DoPrim(PrimOps.Not, Seq(e), Nil, e.tpe)
  private def getType(e1: Expression, e2: Expression) = e2.tpe match {
    case UnknownType => e1.tpe
    case _ => e2.tpe
  }
  def or(e1: Expression, e2: Expression) =
    DoPrim(PrimOps.Or, Seq(e1, e2), Nil, getType(e1, e2))
  def and(e1: Expression, e2: Expression) =
    DoPrim(PrimOps.And, Seq(e1, e2), Nil, getType(e1, e2))
  def bits(e: Expression, high: BigInt, low: BigInt) =
    DoPrim(PrimOps.Bits, Seq(e), Seq(high, low), e.tpe)
  def cat(es: Seq[Expression]): Expression =
    if (es.tail.isEmpty) es.head else {
      val left = cat(es.slice(0, es.length/2))
      val right = cat(es.slice(es.length/2, es.length))
      DoPrim(PrimOps.Cat, Seq(left, right), Nil, ut)
    }

  def childMods = StroberTransforms.context.childMods
  def childInsts = StroberTransforms.context.childInsts
  def instToMod = StroberTransforms.context.instToMod
  def chains = StroberTransforms.context.chains

  def params = StroberCompiler.context.params
  def wrappers(modules: Seq[DefModule]) =
    modules filter (x => StroberCompiler.context.wrappers(x.name))
  def dir = StroberCompiler.context.dir

  def targets(m: DefModule, modules: Seq[DefModule]) = {
    val targets = HashSet[String]()
    def loop(s: Statement): Statement = s match {
      case s: WDefInstance if s.name == "target" =>
        targets += s.module
        s
      case s => s map loop
    }
    m map loop
    modules filter (x => targets(x.name))
  }

  def preorder(heads: Seq[DefModule],
               modules: Seq[DefModule])
               (visit: DefModule => DefModule): Seq[DefModule] = {
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      visited += m.name
      visit(m) +: (modules filter (x =>
        childMods(m.name)(x.name) && !visited(x.name)) flatMap loop)
    }
    heads flatMap loop
  }

  def postorder(heads: Seq[DefModule],
                modules: Seq[DefModule])
                (visit: DefModule => DefModule): Seq[DefModule] = {
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      val res = (modules filter (x =>
        childMods(m.name)(x.name)) flatMap loop)
      if (visited(m.name)) {
        res 
      } else {
        visited += m.name
        res :+ visit(m)
      }
    }
    heads flatMap loop
  }

  def sumWidths(s: Statement)(implicit chainType: ChainType.Value): BigInt =
    chainType match {
      case ChainType.SRAM => s match {
        case s: DefMemory if s.readLatency > 0 && s.depth > 16 =>
          s.depth * bitWidth(s.dataType)
        case s: Block => (s.stmts foldLeft BigInt(0))(_ + sumWidths(_))
        case _ => BigInt(0)
      }
      case _ => s match {
        case s: DefRegister =>
          bitWidth(s.tpe)
        case s: DefMemory if s.readLatency == 0 && s.depth <= 16 =>
          s.depth * bitWidth(s.dataType)
        case s: DefMemory if s.readLatency > 0 =>
          val ew = 1
          val mw = 1
          val aw = chisel3.util.log2Up(s.depth)
          val dw = bitWidth(s.dataType).toInt
          s.readers.size * s.readLatency * (ew + aw + dw) +
          s.writers.size * (s.writeLatency - 1) * (ew + mw + aw + dw) +
          s.readwriters.size * (s.readLatency * (ew + aw + dw) +
            (s.writeLatency - 1) * (ew + mw + dw))
        case s: Block => (s.stmts foldLeft BigInt(0))(_ + sumWidths(_))
        case _ => BigInt(0)
      }
    }
}

private[passes] object Analyses extends firrtl.passes.Pass {
  import Utils._
  def name = "[strober] Analyze Circuit"

  def collectChildren(mname: String, blackboxes: Set[String])(s: Statement): Statement = {
    s match {
      case s: WDefInstance if !blackboxes(s.module) =>
        childInsts(mname) += s.name
        childMods(mname) += s.module
        instToMod(s.name -> mname) = s.module
      case _ =>
    }
    s map collectChildren(mname, blackboxes)
  }

  def collectChildrenMod(blackboxes: Set[String])(m: DefModule) = {
    childInsts(m.name) = ArrayBuffer[String]()
    childMods(m.name) = LinkedHashSet[String]()
    m map collectChildren(m.name, blackboxes)
  }

  def run(c: Circuit) = {
    val blackboxes = (c.modules collect { case m: ExtModule => m.name }).toSet
    c copy (modules = c.modules map collectChildrenMod(blackboxes))
  }
}

private[passes] class DumpChains(conf: File) extends firrtl.passes.Pass {
  import Utils._
  def name = "[strober] Dump Chains"

  private val seqMems = (MemConfReader(conf) map (m => m.name -> m)).toMap

  private def addPad(w: Writer, cw: Int, dw: Int)(chainType: ChainType.Value) {
    (cw - dw) match {
      case 0 =>
      case pad => w write s"${chainType.id} null ${pad} -1\n"
    }
  }

  private def loop(w: Writer, mod: String, path: String, daisyWidth: Int)(chainType: ChainType.Value) {
    chains(chainType) get mod match {
      case Some(chain) if !chain.isEmpty =>
        val (cw, dw) = (chain foldLeft (0, 0)){case ((chainWidth, dataWidth), s) =>
          val dw = dataWidth + (s match {
            case s: WDefInstance =>
              val seqMem = seqMems(s.module)
              chainType match {
                case ChainType.SRAM =>
                  w write s"${chainType.id} ${path}.${seqMem.name}.ram "
                  w write s"${seqMem.width} ${seqMem.depth}\n"
                  seqMem.width.toInt
                case _ => 0 // TODO
              }
            case s: DefMemory => (create_exps(s.name, s.dataType) foldLeft 0){ (totalWidth, mem) =>
              val width = bitWidth(mem.tpe).toInt
              chainType match {
                case ChainType.SRAM =>
                  w write s"${chainType.id} ${path}.${s.name} ${width} ${s.depth}\n"
                  totalWidth + width
                case _ =>
                  (0 until s.depth) map (widx(mem, _)) foreach { e =>
                    w write s"${chainType.id} ${path}.${e.serialize} ${width} -1\n"
                  }
                  totalWidth + s.depth * width
              }
            }
            case s: DefRegister => (create_exps(s.name, s.tpe) foldLeft 0){ (totalWidth, reg) =>
              val width = bitWidth(reg.tpe).toInt
              w write s"${chainType.id} ${path}.${reg.serialize} ${width} -1\n"
              totalWidth + width
            }
          })
          val cw = (Stream from 0 map (chainWidth + _ * daisyWidth) dropWhile (_ < dw)).head
          chainType match {
            case ChainType.SRAM => 
              addPad(w, cw, dw)(chainType)
              (0, 0)
            case _ => (cw, dw)
          }
        }
        chainType match {
          case ChainType.SRAM => 
          case _ => addPad(w, cw, dw)(chainType)
        }
      case _ =>
    }
    childInsts(mod) foreach {child =>
      loop(w, instToMod(child, mod), s"${path}.${child}", daisyWidth)(chainType)}
  }

  def run(c: Circuit) = {
    wrappers(c.modules) foreach {
      case m: Module =>
        val daisyWidth = params(m.name)(DaisyWidth)
        targets(m, c.modules) foreach { target =>
          val file = new File(dir, s"${target.name}.chain")
          val writer = new FileWriter(file)
          ChainType.values.toList foreach loop(writer, target.name, target.name, daisyWidth)
          writer.close
        }
      case m: ExtModule =>
    }
    c
  }
}
