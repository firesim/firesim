package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

// Enum type for replay commands
object SampleInstType extends Enumeration {
  val CYCLE, LOAD, FORCE, POKE, STEP, EXPECT, COUNT = Value
}

abstract class SampleInst
case class Step(n: Int) extends SampleInst
case class Load(node: Node, value: BigInt, off: Option[Int] = None) extends SampleInst
case class Force(node: Node, value: BigInt) extends SampleInst
case class PokePort(node: Bits, value: BigInt) extends SampleInst
case class ExpectPort(node: Bits, value: BigInt) extends SampleInst
case class Count(node: Node, value: BigInt) extends SampleInst

object Sample {
  private val chains = Map(
    ChainType.Trs  -> ArrayBuffer[(Option[Node], Int, Option[Int])](),
    ChainType.Regs -> ArrayBuffer[(Option[Node], Int, Option[Int])](),
    ChainType.SRAM -> ArrayBuffer[(Option[Node], Int, Option[Int])](),
    ChainType.Cntr -> ArrayBuffer[(Option[Node], Int, Option[Int])]()
  )
  private lazy val chainLoop  = transforms.chainLoop

  def addToChain(t: ChainType.Value, signal: Option[Node], width: Int, off: Option[Int] = None) {
    chains(t) += ((signal, width, off))
  }

  private def readChain(t: ChainType.Value, sample: Sample, snap: String, base: Int = 0) = {
    ((0 until chainLoop(t)) foldLeft base){case (offset, i) =>
      val next = (chains(t) foldLeft offset){case (start, (signal, width, idx)) =>
        val end = math.min(start + width, snap.length)
        val value = BigInt(snap.substring(start, end), 2)
        signal match {
          case Some(p) if t == ChainType.Trs => 
            sample addCmd Force(p, value)
          case Some(p) if t == ChainType.Regs => 
            sample addCmd Load(p, value, idx)
          case Some(p) if t == ChainType.SRAM && i < idx.get =>
            sample addCmd Load(p, value, Some(i))
          case Some(p) if t == ChainType.Cntr =>
            sample addCmd Count(p, value)
          case _ =>
        }
        end
      }
      if (t == ChainType.Trs) sample addCmd Step(1)
      // assert(next % daisyWidth == 0)
      next
    }
  }

  def apply(snap: String = "", cycle: Long = -1L) = {
    val sample = new Sample(cycle)
    (ChainType.values.toList filterNot (_ == ChainType.Cntr) foldLeft 0){
      case (base, chainType) => readChain(chainType, sample, snap, base)}
    sample
  }

  def apply(chainType: ChainType.Value, snap: String, cycle: Long) = {
    val sample = new Sample(cycle)
    readChain(chainType, sample, snap)
    sample
  }
}

class Sample(protected[strober] val cycle: Long = -1L) {
  private val cmds = ArrayBuffer[SampleInst]()
  def addCmd(cmd: SampleInst) { cmds += cmd }
  def map[T](f: SampleInst => T) = { cmds map f }
  override def toString = {
    val res = new StringBuilder
    res append "%d cycle: %d\n".format(SampleInstType.CYCLE.id, cycle)
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
      case Count(node, value) => {
        val path = transforms.nameMap(node)
        res append "%d %s %d\n".format(SampleInstType.COUNT.id, path, value)
      }
    }
    res.result
  }
}
