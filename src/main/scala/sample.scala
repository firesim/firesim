package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

// Enum type for replay commands
object SampleInstType extends Enumeration {
  val FIN, LOAD, FORCE, POKE, STEP, EXPECT = Value
}

abstract class SampleInst
case class Step(n: Int) extends SampleInst
case class Load(node: Node, value: BigInt, off: Option[Int] = None) extends SampleInst
case class Force(node: Node, value: BigInt) extends SampleInst
case class PokePort(node: Bits, value: BigInt) extends SampleInst
case class ExpectPort(node: Bits, value: BigInt) extends SampleInst

object Sample {
  private val sramChain = ArrayBuffer[(Option[Node], Int, Option[Int])]()
  private val traceChain = ArrayBuffer[(Option[Node], Int)]()
  private val regChain = ArrayBuffer[(Option[Node], Int, Option[Int])]()
  private lazy val warmingCycles = transforms.warmingCycles

  def addToSRAMChain(signal: Option[Node], width: Int, off: Option[Int] = None) {
    sramChain += ((signal, width, off))
  }

  def addToTraceChain(signal: Option[Node], width: Int) {
    traceChain += ((signal, width))
  }

  def addToRegChain(signal: Option[Node], width: Int, off: Option[Int] = None) {
    regChain += ((signal, width, off))
  }

  def apply(snap: String = "") = {
    val sample = new Sample()
    var start = 0

    for ((signal, width, off) <- sramChain) {
      val end = math.min(start + width, snap.length)
      val value = BigInt(snap.substring(start, end), 2)
      signal match {
        case None =>
        case Some(p) => sample addCmd Load(p, value, off)
      }
      start += width
    }

    for (((signal, width), i) <- traceChain.zipWithIndex) {
      val end = math.min(start + width, snap.length)
      val value = BigInt(snap.substring(start, end), 2)
      signal match {
        case None =>
        case Some(p) => sample addCmd Force(p, value)
      }
      start += width
      val doStep = (i + 1) % (traceChain.size / warmingCycles) == 0
      if (doStep) sample addCmd Step(1)
    }

    for ((signal, width, off) <- regChain) {
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

  def map[T](f: SampleInst => T) = {
    cmds map f
  }

  override def toString = {
    val res = new StringBuilder
    map {
      case Step(n) => res append "%d %d\n".format(SampleInstType.STEP.id, n)
      case Load(node, value, off) => {
        val path = transforms.nameMap(node)
        res append "%d %s %s %d\n".format(SampleInstType.LOAD.id, path, value.toString(16), off.getOrElse(-1))
      }
      case Force(node, value) => {
        val path = transforms.nameMap(node)
        res append "%d %s %s\n".format(SampleInstType.FORCE.id, path, value.toString(16))
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
    res append "%d\n".format(SampleInstType.FIN.id)
    res.result
  }
}
