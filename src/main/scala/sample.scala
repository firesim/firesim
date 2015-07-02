package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

// Enum type for replay commands
object SampleInstType extends Enumeration {
  val FIN, LOAD, POKE, STEP, EXPECT = Value
}

abstract class SampleInst
case class Step(n: Int) extends SampleInst
case class Load(node: Node, value: BigInt, off: Option[Int] = None) extends SampleInst
case class PokePort(node: Bits, value: BigInt) extends SampleInst
case class ExpectPort(node: Bits, value: BigInt) extends SampleInst

object Sample {
  val signals = ArrayBuffer[String]()
  val widths  = ArrayBuffer[Int]()
  val MemRegex = """([\w\.]+)\[(\d+)\]""".r

  def addMapping(signal: String, width: Int) {
    signals += signal
    widths  += width
  }

  def apply(snap: String = "") = {
    val sample = new Sample()
    var start = 0
    for ((signal, i) <- signals.zipWithIndex) {
      val width = widths(i)
      if (signal != "null") {
        val end = math.min(start + width, snap.length)
        val value = BigInt(snap.substring(start, end), 2)
        signal match {
          case MemRegex(name, off) =>
            // sample.cmds += Load(name, value, Some(off.toInt))
          case _ =>
            // sample.cmds += Load(signal, value)
        }
      }
      start += width
    }
    sample
  }
}

class Sample() {
  private val cmds = ArrayBuffer[SampleInst]()
  def addCmd(cmd: SampleInst) {
    cmds += cmd
  }
  override def toString = {
    val res = new StringBuilder
    for (cmd <- cmds) {
      cmd match {
        case Step(n) => res append "%d %d\n".format(SampleInstType.STEP.id, n)
        case Load(node, value, off) => 
        case PokePort(node, value) => {
          val path = transforms.targetName + (node.chiselName stripPrefix transforms.targetPath)
          res append "%d %s %s\n".format(SampleInstType.POKE.id, path, value.toString(16))
        }
        case ExpectPort(node, value) => {
          val path = transforms.targetName + (node.chiselName stripPrefix transforms.targetPath)
          res append "%d %s %s\n".format(SampleInstType.EXPECT.id, path, value.toString(16))
        }
      }
    }
    res append "%d\n".format(SampleInstType.FIN.id)
    res.result
  }
}
