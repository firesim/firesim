package midas
package passes

import midas.core.{SimWrapper, ChainType}
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.{BoolType, create_exps}
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.VerilogRename.verilogRenameN
import Utils._
import MidasTransforms._
import java.io.{File, FileWriter, Writer, StringWriter}

private[passes] class SimulationMapping(
    io: chisel3.Data,
    dir: File,
    childInsts: ChildInsts,
    instModMap: InstModMap,
    chains: Map[ChainType.Value, ChainMap],
    seqMems: Map[String, MemConf])
   (implicit param: config.Parameters) extends firrtl.passes.Pass {
  
  override def name = "[midas] Simulation Mapping"

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

  private def dumpChainMap(target: String)(implicit daisyWidth: Int) {
    val chainFile = new FileWriter(new File(dir, s"$target.chain"))
    ChainType.values.toList foreach loop(chainFile, target, target)
    chainFile.close
  }

  private def initStmt(target: String)(s: Statement): Statement =
    s match {
      case s: WDefInstance if s.name == "target" && s.module == "TargetBox" =>
        s copy (module = target) // replace TargetBox with the actual target module
      case s => s map initStmt(target)
    }

  private def init(info: Info, target: String, main: String)(m: DefModule) = m match {
    case m: Module if m.name == main =>
      val body = initStmt(target)(m.body)
      val stmts = Seq(
        Connect(NoInfo, wsub(wref("target"), "targetFire"), wref("fire", BoolType)),
        Connect(NoInfo, wsub(wref("target"), "daisyReset"), wref("reset", BoolType))) ++
      (if (!param(EnableSnapshot)) Nil else Seq(
        Connect(NoInfo, wsub(wref("io"), "daisy"), wsub(wref("target"), "daisy"))))
      Some(m copy (info = info, body = Block(body +: stmts)))
    case m: Module => Some(m)
    case m: ExtModule => None
  }

  def run(c: Circuit) = {
    lazy val sim = new SimWrapper(io)
    val chirrtl = Parser parse (chisel3.Driver emit (() => sim))
    val annotations = new AnnotationMap(Nil)
    val writer = new StringWriter
    // val writer = new FileWriter(new File("SimWrapper.ir"))
    val circuit = renameMods((new InlineCompiler compile (
      CircuitState(chirrtl, ChirrtlForm), writer)).circuit, Namespace(c))
    val modules = c.modules ++ (circuit.modules flatMap init(c.info, c.main, circuit.main))
    // writer.close
    dumpChainMap(c.main)(sim.daisyWidth)
    new WCircuit(circuit.info, modules, circuit.main, sim.io)
  }
}
