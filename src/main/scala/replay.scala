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
    (scala.io.Source.fromFile(filename).getLines foldLeft new Sample){(sample, line) =>
      val tokens = line split " "
      val cmd = SampleInstType(tokens.head.toInt)
      cmd match {
        case SampleInstType.FIN => 
          samples += sample 
          new Sample
        case SampleInstType.LOAD =>
          val value = BigInt(tokens.init.last, 16)
          val off = tokens.last.toInt
          (signalMap get tokens.tail.head) match {
            case None =>
            case Some(node) => sample addCmd Load(node, value, if (off < 0) None else Some(off))
          }
          sample
        case SampleInstType.FORCE => 
          val node = signalMap(tokens.tail.head)
          val value = BigInt(tokens.last, 16)
          sample addCmd Force(node, value)
          sample
        case SampleInstType.POKE => 
          val node = signalMap(tokens.tail.head) match {case b: Bits => b}
          val value = BigInt(tokens.last, 16)
          sample addCmd PokePort(node, value)
          sample
        case SampleInstType.STEP => 
          sample addCmd Step(tokens.last.toInt)
          sample
        case SampleInstType.EXPECT => 
          val node = signalMap(tokens.tail.head) match {case b: Bits => b}
          val value = BigInt(tokens.last, 16)
          sample addCmd ExpectPort(node, value)
          sample
      }
    }
  }

  def loadWires(node: Node, value: BigInt, off: Option[Int]) {
    def loadff(path: String, v: BigInt) = (matchMap get path) match {
      case Some(p) => pokePath(p, v) case None => // skip 
    }
    if (node.needWidth == 1) {
      val path = dumpName(node) + (off map ("[" + _ + "]") getOrElse "") 
      loadff(path, value)
    } else (0 until node.needWidth) foreach { idx =>
      val path = dumpName(node) + (off map ("[" + _ + "]") getOrElse "") + "[" + idx + "]"
      loadff(path, (value >> idx) & 0x1)
    }
  } 

  def run {
    val startTime = System.nanoTime
    samples.zipWithIndex foreach {case (sample, i) =>
      println(s"START SAMPLE #${i}")
      sample map {
        case Step(n) => step(n)
        case Force(node, value) => // Todo 
        case Load(node, value, off) => node match {
          case mem: Mem[_] if mem.seqRead && !mem.isInline => off match {
            case None => 
              pokePath(s"${dumpName(mem)}.sram.O1", value)
            case Some(p) if p < mem.n => 
              pokePath(s"${dumpName(mem)}.sram.memory[${p}]", value)
            case _ => // skip
          }
          case mem: Mem[_] if off == None => // skip
          case _ => if (matchMap.isEmpty) pokeNode(node, value, off) else loadWires(node, value, off)
        }
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
    if (arg.slice(0, 7) == "+match=") {
      val lines = scala.io.Source.fromFile(arg.substring(7)).getLines
      lines foreach { line =>
        val tokens = line split " "
        matchMap(tokens.head) = tokens.last
      }
    }
    if (arg.slice(0, 8) == "+sample=") {
      sampleFile = Some(arg.substring(8))
    }
  }

  Driver.dfs {
    case node: Delay       => signalMap(dumpName(node)) = node
    case node if node.isIo => signalMap(dumpName(node)) = node
    case _ =>
  }
  loadSamples(sampleFile match {case None => basedir + c.name + ".sample" case Some(f) => f})
  run
}
