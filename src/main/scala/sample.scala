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
  private lazy val warmCycles = transforms.chainSize(ChainType.Trs)
  private val chains = Map(
    ChainType.Regs -> ArrayBuffer[(Option[Node], Int, Option[Int])](),
    ChainType.Trs  -> ArrayBuffer[(Option[Node], Int, Option[Int])](),
    ChainType.SRAM -> ArrayBuffer[(Option[Node], Int, Option[Int])](),
    ChainType.Cntr -> ArrayBuffer[(Option[Node], Int, Option[Int])]()
  )

  def addToChain(t: ChainType.Value, signal: Option[Node], width: Int, off: Option[Int] = None) {
    chains(t) += ((signal, width, off))
  }

  private lazy val traceLen = chains(ChainType.Trs).size / warmCycles
  def apply(snap: String = "") = {
    val sample = new Sample()

    val sram_off = (chains(ChainType.SRAM) foldLeft 0){case (start, (signal, width, off)) =>
      val end = math.min(start + width, snap.length)
      val value = BigInt(snap.substring(start, end), 2)
      signal match {
        case None =>
        case Some(p) => sample addCmd Load(p, value, off)
      }
      start + width
    }

    val tr_off = (chains(ChainType.Trs).zipWithIndex foldLeft sram_off){case (start, ((signal, width, _), i)) =>
      val end = math.min(start + width, snap.length)
      val value = BigInt(snap.substring(start, end), 2)
      signal match {
        case None =>
        case Some(p) => sample addCmd Force(p, value)
      }
      val doStep = (i + 1) % traceLen == 0
      if (doStep) sample addCmd Step(1)
      start + width
    }

    val reg_off = (chains(ChainType.Regs) foldLeft tr_off){case (start, (signal, width, off)) =>
      val end = math.min(start + width, snap.length)
      val value = BigInt(snap.substring(start, end), 2)
      signal match {
        case None =>
        case Some(p) => sample addCmd Load(p, value, off)
      }
      start + width
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
        res append "%d %s %x %d\n".format(SampleInstType.LOAD.id, path, value, off getOrElse -1)
      }
      case Force(node, value) => {
        val path = transforms.nameMap(node)
        res append "%d %s %x\n".format(SampleInstType.FORCE.id, path, value)
      }
      case PokePort(node, value) => {
        val path = transforms.nameMap(node)
        res append "%d %s %x\n".format(SampleInstType.POKE.id, path, value)
      }
      case ExpectPort(node, value) => {
        val path = transforms.nameMap(node)
        res append "%d %s %x\n".format(SampleInstType.EXPECT.id, path, value)
      }
    }
    res append "%d\n".format(SampleInstType.FIN.id)
    res.result
  }
}
