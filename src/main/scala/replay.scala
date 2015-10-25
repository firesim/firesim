package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.io.Source

class Replay[+T <: Module](c: T, args: Seq[String] = Seq(), isTrace: Boolean = true) extends Tester(c, isTrace) {
  private val basedir = Driver.targetDir
  private val signalMap = HashMap[String, Node]()
  private val matchMap = HashMap[String, String]()
  private val samples = ArrayBuffer[Sample]()
  private var sampleFile: Option[String] = None

  def loadSamples(filename: String) {
    samples += new Sample
    (scala.io.Source.fromFile(filename).getLines foldLeft false){(forced, line) =>
      val tokens = line split " "
      val cmd = SampleInstType(tokens.head.toInt)
      cmd match {
        case SampleInstType.FIN => 
          samples += new Sample
          false
        case SampleInstType.LOAD =>
          val value = BigInt(tokens.init.last, 16)
          val off = tokens.last.toInt
          (signalMap get tokens.tail.head) match {
            case None =>
            case Some(node) => 
              samples.last addCmd Load(node, value, if (off < 0) None else Some(off))
          }
          false
        case SampleInstType.FORCE => 
          val node = signalMap(tokens.tail.head)
          val value = BigInt(tokens.last, 16)
          samples.last addCmd Force(node, value)
          true
        case SampleInstType.POKE => 
          val node = signalMap(tokens.tail.head).asInstanceOf[Bits]
          val value = BigInt(tokens.last, 16)
          samples.last addCmd PokePort(node, value)
          false
        case SampleInstType.STEP => 
          if (!forced || !Driver.isInlineMem) samples.last addCmd Step(tokens.last.toInt)
          false
        case SampleInstType.EXPECT => 
          val node = signalMap(tokens.tail.head).asInstanceOf[Bits]
          val value = BigInt(tokens.last, 16)
          samples.last addCmd ExpectPort(node, value)
          false
      }
    }
  }

  def loadWires(node: Node, value: BigInt, off: Option[Int]) {
    def loadsram(path: String, v: BigInt, off: Int) {
      (matchMap get path) match {
        case None => // skip
        case Some(p) => pokePath("%s.memory[%d]".format(p, off), v) 
      }
    }
    def loadff(path: String, v: BigInt) {
      (matchMap get path) match {
        case None => // skip
        case Some(p) => pokePath(p, v)
      }
    }
    node match {
      case mem: Mem[_] if mem.seqRead =>
        loadsram(dumpName(mem), value, off.get)
      case _ if (node.needWidth == 1) => 
        val path = dumpName(node) + (off map ("[" + _ + "]") getOrElse "") 
        loadff(path, value)
      case _ => (0 until node.needWidth) foreach { idx =>
        val path = dumpName(node) + (off map ("[" + _ + "]") getOrElse "") + "[" + idx + "]"
        loadff(path, (value >> idx) & 0x1)
      }
    }
  } 

  def run {
    val startTime = System.nanoTime
    samples.zipWithIndex foreach { case (sample, i) =>
      println(s"START SAMPLE #${i}")
      sample map {
        case Step(n) => step(n)
        case Force(node, value) => node match {
          case mem: Mem[_] if mem.seqRead => if (!Driver.isInlineMem) {
            pokePath("%s.sram.A1".format(dumpName(mem)), value, true)
            pokePath("%s.sram.WEB1".format(dumpName(mem)), BigInt(1), true)
          } else {
            pokeNode(findSRAMRead(mem)._1, value)
          }
          case _ => // Todo
        }
        case Load(node, value, off) => if (matchMap.isEmpty) {
            node match {
              case mem: Mem[_] if mem.seqRead && !Driver.isInlineMem && off.get < mem.n =>
                pokePath("%s.sram.memory[%d]".format(dumpName(mem), off.get), value)
              case _ => 
                pokeNode(node, value, off)
            }
          } else loadWires(node, value, off)
        case PokePort(node, value) => poke(node, value)
        case ExpectPort(node, value) => expect(node, value)
      }
    }
    val endTime = System.nanoTime
    val simTime = (endTime - startTime) / 1000000000.0
    val simSpeed = t / simTime
    ChiselError.info("Time elapsed = %.1f s, Simulation Speed = %.2f Hz".format(simTime, simSpeed))
  }

  args foreach { arg =>
    if (arg.size >= 7 && arg.substring(0, 7) == "+match=") {
      val lines = scala.io.Source.fromFile(arg.substring(7)).getLines
      lines foreach { line =>
        val tokens = line split " "
        matchMap(tokens.head) = tokens.last
      }
    }
    if (arg.size >= 8 && arg.substring(0, 8) == "+sample=") {
      sampleFile = Some(arg.substring(8))
    }
  }

  Driver.dfs {
    case node: Delay       => signalMap(node.chiselName) = node
    case node if node.isIo => signalMap(node.chiselName) = node
    case _ =>
  }
  loadSamples(sampleFile match { case None => basedir + c.name + ".sample" case Some(f) => f})
  run
}
