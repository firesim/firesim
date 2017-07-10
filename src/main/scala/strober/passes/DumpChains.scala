package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Utils.create_exps
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.VerilogRename.verilogRenameN
import strober.core.ChainType
import java.io.{File, FileWriter}

class DumpChains(
    dir: File,
    meta: StroberMetaData)
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
    meta.chains(chainType) get mod match {
      case Some(chain) if !chain.isEmpty =>
        val id = chainType.id
        val (cw, dw) = (chain foldLeft (0, 0)){case ((chainWidth, dataWidth), s) =>
          val dw = dataWidth + (s match {
            case s: DefMemory if s.readLatency == 1 =>
              val prefix = s"$path.${s.name}"
              val width = bitWidth(s.dataType)
              (chainType: @unchecked) match {
                case ChainType.SRAM =>
                  chainFile write s"$id $prefix.ram ${width} ${s.depth}\n"
                  width.toInt
                case ChainType.Trace =>
                  val addrWidth = chisel3.util.log2Up(s.depth.toInt)
                  /* s.readers.indices foreach (i =>
                    chainFile write s"$id $prefix.reg_R$i $addrWidth -1\n")
                  s.readwriters.indices foreach (i =>
                    chainFile write s"$id $prefix.reg_RW$i $addrWidth -1\n") */
                  s.readers.indices foreach (i =>
                    chainFile write s"$id $prefix.R${i}_data ${width} -1\n")
                  s.readwriters.indices foreach (i =>
                    chainFile write s"$id $prefix.RW${i}_rdata ${width} -1\n")
                  (s.readers.size + s.readwriters.size) * (/* addrWidth + */width.toInt)
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
    meta.childInsts(mod) foreach (child => loop(
      chainFile, meta.instModMap(child, mod), s"${path}.${child}")(chainType))
  }

  def run(c: Circuit) = {
    implicit val daisyWidth = param(core.DaisyWidth)
    val chainFile = new FileWriter(new File(dir, s"${c.main}.chain"))
    ChainType.values.toList foreach loop(chainFile, c.main, c.main)
    chainFile.close
    c
  }
}
