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
  val chains = ArrayBuffer[(Option[Node], Int, Option[Int])]()

  def addToChains(signal: Option[Node], width: Int, off: Option[Int] = None) {
    chains += ((signal, width, off))
  }

  def apply(snap: String = "") = {
    val sample = new Sample()
    var start = 0
    for ((signal, width, off) <- chains) {
      val end = math.min(start + width, snap.length)
      val value = BigInt(snap.substring(start, end), 2)
      signal match {
        case None =>
        case Some(p) => sample addCmd Load(p, value, off)
      }
      start += width
    }
    sample
  }
}

class Sample {
  private val cmds = ArrayBuffer[SampleInst]()

  def addCmd(cmd: SampleInst) {
    cmds += cmd
  }

  def foreach(f: SampleInst => Unit) {
    cmds foreach f
  }

  override def toString = {
    val res = new StringBuilder
    for (cmd <- cmds) {
      cmd match {
        case Step(n) => res append "%d %d\n".format(SampleInstType.STEP.id, n)
        case Load(node, value, off) => {
          val path = transforms.nameMap(node)
          res append "%d %s %s %d\n".format(SampleInstType.LOAD.id, path, value.toString(16), off.getOrElse(-1))
        }
        case PokePort(node, value) => {
          val path = transforms.nameMap(node)
          res append "%d %s %s\n".format(SampleInstType.POKE.id, path, value.toString(16))
        }
        case ExpectPort(node, value) => {
          val path = transforms.nameMap(node)
          res append "%d %s %s\n".format(SampleInstType.EXPECT.id, path, value.toString(16))
        }
      }
    }
    res append "%d\n".format(SampleInstType.FIN.id)
    res.result
  }
}
