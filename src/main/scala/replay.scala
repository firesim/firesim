package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.io.Source

class Replay[+T <: Module](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  private val basedir = ensureDir(Driver.targetDir)
  private val signalMap = HashMap[String, Node]()
  private val samples = ArrayBuffer[Sample]()

  def loadSamples(filename: String) {
    samples += new Sample
    val lines = scala.io.Source.fromFile(basedir+filename).getLines
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

  def run {
    for (sample <- samples) {
      sample map {
        case Step(n) => step(n)
        case Load(node, value, off) => pokeBits(node, value, off.getOrElse(-1))
        case PokePort(node, value) => pokeBits(node, value)
        case ExpectPort(node, value) => expect(node, value)
      }
    }
  }

  Driver.dfs { node =>
    if (node.isInObject) signalMap(dumpName(node)) = node
  }
  loadSamples(c.name + ".sample")
  run
}
