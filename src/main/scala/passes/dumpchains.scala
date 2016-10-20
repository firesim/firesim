package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.passes.bitWidth
import firrtl.Utils.create_exps
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.VerilogRename.verilogRenameN
import StroberTransforms._
import java.io.{File, FileWriter, Writer}

private[passes] class DumpChains(
    childInsts: ChildInsts,
    instModMap: InstModMap,
    chains: Map[ChainType.Value, ChainMap],
    dir: java.io.File,
    seqMems: Map[String, MemConf]) extends firrtl.passes.Pass {

  def name = "[strober] dump chains"

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
    childInsts(mod) foreach (child =>
      loop(w, instModMap(child, mod), s"${path}.${child}", daisyWidth)(chainType))
  }

  private object TraceType extends Enumeration {
    val InTr = Value(ChainType.values.size)
    val OutTr = Value(ChainType.values.size + 1)
  }

  private def dumpTraceMap(ioMap: Map[chisel3.Bits, String],
                           trType: TraceType.Value)
                          (arg: (chisel3.Bits, Int))
                          (implicit channelWidth: Int) = arg match {
    case (wire, id) => s"${trType.id} ${ioMap(wire)} ${id} ${SimUtils.getChunks(wire)}\n"
  }

  def run(c: Circuit) = {
    StroberCompiler.context.shims foreach { shim =>
      val sim = shim.sim.asInstanceOf[SimWrapper[chisel3.Module]]
      val target = sim.target.name
      val ioMap = (sim.io.inputs ++ sim.io.outputs).toMap
      implicit val channelWidth = sim.channelWidth
      val file = new File(dir, s"$target.chain")
      val writer = new FileWriter(file)
      val sb = new StringBuilder
      ChainType.values.toList foreach loop(writer, target, target, sim.daisyWidth)
      shim.IN_TR_ADDRS map dumpTraceMap(ioMap, TraceType.InTr) addString sb
      shim.OUT_TR_ADDRS map dumpTraceMap(ioMap, TraceType.OutTr) addString sb
      writer write sb.result
      writer.close
    }
    c
  }
}
