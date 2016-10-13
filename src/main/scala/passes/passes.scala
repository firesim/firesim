package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.bitWidth
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.VerilogRename.verilogRenameN
import firrtl.Utils.{sub_type, field_type, create_exps}
import scala.collection.mutable.{ArrayBuffer, HashSet, LinkedHashSet}
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

private[passes] class DumpChains(seqMems: Map[String, MemConf]) extends firrtl.passes.Pass {
  import Utils._
  def name = "[strober] Dump Chains"

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
              val id = chainType.id
              val prefix = s"$path.${seqMem.name}"
              chainType match {
                case ChainType.SRAM =>
                  w write s"$id $prefix.ram ${seqMem.width} ${seqMem.depth}\n"
                  seqMem.width.toInt
                case _ =>
                  val addrWidth = chisel3.util.log2Up(seqMem.depth.toInt)
                  seqMem.readers.indices foreach (i =>
                    w write s"$id $prefix.reg_R$i $addrWidth -1\n")
                  seqMem.readwriters.indices foreach (i =>
                    w write s"$id $prefix.reg_RW$i $addrWidth -1\n")
                  /* seqMem.readers.indices foreach (i =>
                    w write s"$id $prefix ${seqMem.width} -1\n")
                  seqMem.readwriters.indices foreach (i =>
                    w write s"$id $prefix ${seqMem.width} -1\n") */
                  (seqMem.readers.size + seqMem.readwriters.size) * (addrWidth /*+ seqMem.width.toInt*/)
              }
            case s: DefMemory => (create_exps(s.name, s.dataType) foldLeft 0){ (totalWidth, mem) =>
              val name = verilogRenameN(loweredName(mem))
              val width = bitWidth(mem.tpe).toInt
              chainType match {
                case ChainType.SRAM =>
                  w write s"${chainType.id} $path.$name $width ${s.depth}\n"
                  totalWidth + width
                case _ => totalWidth + (((0 until s.depth) foldLeft 0){ (memWidth, i) =>
                  w write s"${chainType.id} $path.$name[$i] $width -1\n"
                  memWidth + width
                })
              }
            }
            case s: DefRegister => (create_exps(s.name, s.tpe) foldLeft 0){ (totalWidth, reg) =>
              val name = verilogRenameN(loweredName(reg))
              val width = bitWidth(reg.tpe).toInt
              w write s"${chainType.id} $path.$name $width -1\n"
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

  object TraceType extends Enumeration {
    val InTr = Value(ChainType.values.size)
    val OutTr = Value(ChainType.values.size + 1)
  }

  def run(c: Circuit) = {
    StroberCompiler.context.shims foreach { shim =>
      val sim = shim.sim.asInstanceOf[SimWrapper[chisel3.Module]]
      val target = sim.target.name
      val nameMap = (sim.io.inputs ++ sim.io.outputs).toMap
      val daisyWidth = sim.daisyWidth
      implicit val channelWidth = sim.channelWidth
      def dumpTraceMap(t: TraceType.Value)(arg: (chisel3.Bits, Int)) = arg match {
        case (wire, id) => s"${t.id} ${nameMap(wire)} ${id} ${SimUtils.getChunks(wire)}\n" }
      val file = new File(dir, s"$target.chain")
      val writer = new FileWriter(file)
      val sb = new StringBuilder
      ChainType.values.toList foreach loop(writer, target, target, daisyWidth)
      shim.IN_TR_ADDRS map dumpTraceMap(TraceType.InTr) addString sb
      shim.OUT_TR_ADDRS map dumpTraceMap(TraceType.OutTr) addString sb
      writer write sb.result
      writer.close
    }
    c
  }
}
