package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Utils.create_exps
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.VerilogRename.verilogRenameN
import midas.passes.Utils._
import midas.passes.MidasTransforms._
import strober.core.ChainType
import java.io.{File, FileWriter}

class DumpChains(
    dir: File,
    childInsts: ChildInsts,
    instModMap: InstModMap,
    chains: Map[core.ChainType.Value, ChainMap],
    seqMems: Map[String, midas.passes.MemConf])
   (implicit param: config.Parameters) extends firrtl.passes.Pass {
  
  override def name = "[strober] Dump Daisy Chains"

  private def addPad(chainFile: FileWriter, cw: Int, dw: Int)(chainType: ChainType.Value) {
    (cw - dw) match {
      case 0 =>
      case pad => chainFile write s"${chainType.id} null ${pad} -1\n"
    }
  }

  private def loop(chainFile: FileWriter,
                   mod: String,
                   path: String)
                  (chainType: ChainType.Value)
                  (implicit daisyWidth: Int) {
    chains(chainType) get mod match {
      case Some(chain) if !chain.isEmpty =>
        val id = chainType.id
        val (cw, dw) = (chain foldLeft (0, 0)){case ((chainWidth, dataWidth), s) =>
          val dw = dataWidth + (s match {
            case s: WDefInstance =>
              val seqMem = seqMems(s.module)
              val prefix = s"$path.${s.name}"
              chainType match {
                case ChainType.SRAM =>
                  chainFile write s"$id $prefix.ram ${seqMem.width} ${seqMem.depth}\n"
                  seqMem.width.toInt
                case _ =>
                  val addrWidth = chisel3.util.log2Up(seqMem.depth.toInt)
                  /* seqMem.readers.indices foreach (i =>
                    chainFile write s"$id $prefix.reg_R$i $addrWidth -1\n")
                  seqMem.readwriters.indices foreach (i =>
                    chainFile write s"$id $prefix.reg_RW$i $addrWidth -1\n") */
                  seqMem.readers.indices foreach (i =>
                    chainFile write s"$id $prefix.R${i}_data ${seqMem.width} -1\n")
                  seqMem.readwriters.indices foreach (i =>
                    chainFile write s"$id $prefix.RW${i}_rdata ${seqMem.width} -1\n")
                  (seqMem.readers.size + seqMem.readwriters.size) * (/* addrWidth + */seqMem.width.toInt)
              }
            case s: DefMemory => (create_exps(s.name, s.dataType) foldLeft 0){ (totalWidth, mem) =>
              val name = verilogRenameN(loweredName(mem))
              val width = bitWidth(mem.tpe).toInt
              chainType match {
                case ChainType.SRAM =>
                  chainFile write s"$id $path.$name $width ${s.depth}\n"
                  totalWidth + width
                case _ => totalWidth + (((0 until s.depth) foldLeft 0){ (memWidth, i) =>
                  chainFile write s"$id $path.$name[$i] $width -1\n"
                  memWidth + width
                })
              }
            }
            case s: DefRegister => (create_exps(s.name, s.tpe) foldLeft 0){ (totalWidth, reg) =>
              val name = verilogRenameN(loweredName(reg))
              val width = bitWidth(reg.tpe).toInt
              chainFile write s"$id $path.$name $width -1\n"
              totalWidth + width
            }
          })
          val cw = (Stream from 0 map (chainWidth + _ * daisyWidth) dropWhile (_ < dw)).head
          chainType match {
            case ChainType.SRAM => 
              addPad(chainFile, cw, dw)(chainType)
              (0, 0)
            case _ => (cw, dw)
          }
        }
        chainType match {
          case ChainType.SRAM => 
          case _ => addPad(chainFile, cw, dw)(chainType)
        }
      case _ =>
    }
    childInsts(mod) foreach (child => loop(
      chainFile, instModMap(child, mod), s"${path}.${child}")(chainType))
  }

  def run(c: Circuit) = {
    implicit val daisyWidth = param(core.DaisyWidth)
    val chainFile = new FileWriter(new File(dir, s"${c.main}.chain"))
    ChainType.values.toList foreach loop(chainFile, c.main, c.main)
    chainFile.close
    c
  }
}
