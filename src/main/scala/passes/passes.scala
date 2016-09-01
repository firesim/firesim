package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.{create_exps, req_num_bits, long_BANG}
import scala.collection.mutable.{Stack, HashSet, ArrayBuffer} 
import scala.collection.immutable.ListSet
import java.io.{File, FileWriter, Writer}

private[passes] object Utils {
  val ut = UnknownType
  val uw = UnknownWidth
  val ug = UNKNOWNGENDER
  
  def wref(s: String, t: Type = ut, k: Kind = ExpKind()) = WRef(s, t, k, ug)
  def wsub(e: Expression, s: String, t: Type = ut) = WSubField(e, s, t, ug)
  def widx(e: Expression, i: Int, t: Type = ut) = WSubIndex(e, i, t, ug)
  def not(e: Expression) = DoPrim(PrimOps.Not, Seq(e), Nil, ut)
  def or(e1: Expression, e2: Expression) = DoPrim(PrimOps.Or, Seq(e1, e2), Nil, ut)
  def and(e1: Expression, e2: Expression) = DoPrim(PrimOps.And, Seq(e1, e2), Nil, ut)
  def bits(e: Expression, high: BigInt, low: BigInt) = DoPrim(PrimOps.Bits, Seq(e), Seq(high, low), ut)
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

  def chainLen = StroberCompiler.context.chainLen
  def chainLoop = StroberCompiler.context.chainLoop
  def params = StroberCompiler.context.params
  def wrappers(modules: Seq[DefModule]) =
    modules filter (x => StroberCompiler.context.wrappers(x.name))
  def dir = StroberCompiler.context.dir

  def targets(m: Module, modules: Seq[DefModule]) = {
    def loop(s: Statement): Seq[String] = s match {
      case s: WDefInstance if s.name == "target" => Seq(s.module)
      case s: Block => s.stmts flatMap loop
      case _ => Nil
    }
    val mods = loop(m.body).toSet
    modules filter (x => mods(x.name))
  }

  def preorder(heads: Seq[DefModule], modules: Seq[DefModule])(visit: DefModule => DefModule): Seq[DefModule] = {
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      visited += m.name
      visit(m) +: (modules filter (x => childMods(m.name)(x.name) && !visited(x.name)) flatMap loop)
    }
    heads flatMap loop
  }

  def postorder(heads: Seq[DefModule], modules: Seq[DefModule])(visit: DefModule => DefModule): Seq[DefModule] = {
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      val res = (modules filter (x => childMods(m.name)(x.name)) flatMap loop)
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
          s.depth * long_BANG(s.dataType)
        case s: Block => (s.stmts foldLeft BigInt(0))(_ + sumWidths(_))
        case _ => BigInt(0)
      }
      case _ => s match {
        case s: DefRegister =>
          long_BANG(s.tpe)
        case s: DefMemory if s.readLatency == 0 && s.depth <= 16 =>
          s.depth * long_BANG(s.dataType)
        case s: DefMemory if s.readLatency > 0 =>
          val ew = 1
          val mw = 1
          val aw = req_num_bits(s.depth)
          val dw = long_BANG(s.dataType).toInt
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
  
  def collectChildren(m: Module) {
    def collectChildren(s: Statement): Seq[(String, String)] = s match {
      case s: WDefInstance => Seq(s.name -> s.module)
      case s: Block => s.stmts flatMap collectChildren
      case s => Nil
    }
    val (insts, mods) = collectChildren(m.body).unzip
    childInsts(m.name) = ListSet(insts:_*)
    childMods(m.name) = ListSet(mods:_*)
    instToMod ++= insts zip mods
  }

  def run(c: Circuit) = {
    c.modules foreach {
      case m: Module => collectChildren(m)
      case m: ExtModule =>
    }
    c
  }
}

private[passes] object DumpChains extends firrtl.passes.Pass {
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
            case s: DefMemory if chainType == ChainType.SRAM =>
              val width = long_BANG(s.dataType).toInt
              w write s"${chainType.id} ${path}.${s.name} ${width} ${s.depth}\n"
              width
            case s: DefMemory if s.readLatency > 0 =>
              val ew = 1
              val mw = 1
              val aw = req_num_bits(s.depth)
              val dw = long_BANG(s.dataType).toInt
              ((s.readers foldLeft 0){(sum, reader) =>
                (0 until s.readLatency) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${reader}.en $ew $i\n")
                (0 until s.readLatency) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${reader}.addr $aw $i\n")
                (0 until s.readLatency) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${reader}.data $dw $i\n")
                sum + s.readLatency * (ew + aw + dw)
              }) + ((s.writers foldLeft 0){(sum, writer) =>
                (0 until (s.writeLatency-1)) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${writer}.en $ew $i\n")
                (0 until (s.writeLatency-1)) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${writer}.mask $mw $i\n")
                (0 until (s.writeLatency-1)) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${writer}.addr $aw $i\n")
                (0 until (s.writeLatency-1)) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${writer}.data $dw $i\n")
                sum + (s.readLatency - 1) * (ew + mw + aw + dw)
              }) + ((s.readwriters foldLeft 0){(sum, readwriter) =>
                (0 until s.readLatency) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${readwriter}.en $ew $i\n")
                (0 until s.readLatency) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${readwriter}.raddr $aw $i\n")
                (0 until s.readLatency) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${readwriter}.rdata $dw $i\n")
                (0 until (s.writeLatency-1)) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${readwriter}.wmode $ew $i\n")
                (0 until (s.writeLatency-1)) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${readwriter}.wmask $mw $i\n")
                (0 until (s.writeLatency-1)) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${readwriter}.waddr $aw $i\n")
                (0 until (s.writeLatency-1)) foreach (i =>
                  w write s"${chainType.id} ${path}.${s.name}.${readwriter}.wdata $dw $i\n")
                sum + s.readLatency * (ew + aw + dw) + (s.writeLatency - 1) * (ew + mw + aw + dw)
              })
            case s: DefMemory =>
              val width = long_BANG(s.dataType).toInt
              create_exps(s.name, s.dataType) foreach { mem =>
                (0 until s.depth) map (widx(mem, _)) foreach { e =>
                  w write s"${chainType.id} ${path}.${e.serialize} ${width} -1\n"
                }
              }
              width
            case s: DefRegister =>
              val width = long_BANG(s.tpe).toInt
              create_exps(s.name, s.tpe) foreach { reg =>
                w write s"${chainType.id} ${path}.${reg.serialize} ${width} -1\n"
              }
              width
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
    childInsts(mod) foreach (child =>
      loop(w, instToMod(child), s"${path}.${child}", daisyWidth)(chainType))
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
