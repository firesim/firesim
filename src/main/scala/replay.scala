package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.io.Source

class Replay[+T <: Module](c: T, matchFile: Option[String] = None, isTrace: Boolean = true) extends Tester(c, isTrace) {
  private val basedir = Driver.targetDir
  private val signalMap = HashMap[String, Node]()
  private val samples = ArrayBuffer[Sample]()

  def loadSamples(filename: String) {
    samples += new Sample
    val lines = scala.io.Source.fromFile(basedir+"/"+filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      val cmd = SampleInstType(tokens.head.toInt)
      cmd match {
        case SampleInstType.FIN => samples += new Sample
        case SampleInstType.LOAD => {
          val node = signalMap(tokens.tail.head)
          val value = BigInt(tokens.init.last, 16)
          val off = tokens.last.toInt
          samples.last addCmd Load(node, value, if (off < 0) None else Some(off))
        }
        case SampleInstType.POKE => {
          val node = signalMap(tokens.tail.head).asInstanceOf[Bits]
          val value = BigInt(tokens.last, 16)
          samples.last addCmd PokePort(node, value)
        }
        case SampleInstType.STEP => 
          samples.last addCmd Step(tokens.last.toInt)
        case SampleInstType.EXPECT => {
          val node = signalMap(tokens.tail.head).asInstanceOf[Bits]
          val value = BigInt(tokens.last, 16)
          samples.last addCmd ExpectPort(node, value)
        }
      }
    }
  }

  private val matchMap = matchFile match {
    case None => Map[String, String]()
    case Some(f) => {
      val lines = scala.io.Source.fromFile(f).getLines
      (lines map { line =>
        val tokens = line split " "
        tokens.head -> tokens.last
      }).toMap
    }
  }

  def loadWires(node: Node, value: BigInt, off: Option[Int]) {
    def loadsram(path: String, v: BigInt, off: Int) {
      try {
        pokePath("%s.memory[%d]".format(matchMap(path), off), v) 
      } catch {
        case e: NoSuchElementException => // skip
      }
    }
    def loadff(path: String, v: BigInt) {
      try {
         pokePath(matchMap(path), v) 
      } catch {
        case e: NoSuchElementException => // skip
      }
    }
    node match {
      case mem: Mem[_] if mem.seqRead =>
        loadsram(dumpName(mem), value, off.get)
      case _ if (node.needWidth == 1) => 
        val path = dumpName(node) + (off map ("[" + _ + "]") getOrElse "") + ".Q" 
        loadff(path, value)
      case _ => (0 until node.needWidth) foreach { idx =>
        val path = dumpName(node) + (off map ("[" + _ + "]") getOrElse "") + "[" + idx + "].Q" 
        loadff(path, (value >> idx) & 0x1)
      }
    }
  } 

  def run {
    samples foreach (_ map {
      case Step(n) => step(n)
      case Load(node, value, off) => matchFile match {
        case None => pokeNode(node, value, off)
        case Some(f) => loadWires(node, value, off)
      }
      case PokePort(node, value) => poke(node, value)
      case ExpectPort(node, value) => expect(node, value)
    })
  }

  Driver.dfs { node =>
    if (node.isReg || node.isIo) signalMap(node.chiselName) = node
  }
  loadSamples(c.name + ".sample")
  run
}
