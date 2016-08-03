package strober
package testers

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}

// Enum type for replay commands
object SampleInstType extends Enumeration {
  val CYCLE, LOAD, FORCE, POKE, STEP, EXPECT, COUNT = Value
}

abstract class SampleInst
case class Step(n: Int) extends SampleInst
case class Load(node: String, value: BigInt, off: Int) extends SampleInst
case class Force(node: String, value: BigInt) extends SampleInst
case class PokePort(node: String, value: BigInt) extends SampleInst
case class ExpectPort(node: String, value: BigInt) extends SampleInst
case class Count(node: String, value: BigInt) extends SampleInst

private[testers] class DaisyChainReader(
    chainFile: String, chainLoop: Map[ChainType.Value, Int], daisyWidth: Int) {
  /* (Signal, Width, Index) */
  private val chains = Map(
    ChainType.Trace -> ArrayBuffer[(Option[String], Int, Int)](),
    ChainType.Regs  -> ArrayBuffer[(Option[String], Int, Int)](),
    ChainType.SRAM  -> ArrayBuffer[(Option[String], Int, Int)](),
    ChainType.Cntr  -> ArrayBuffer[(Option[String], Int, Int)]()
  )

  private def readChain(t: ChainType.Value, sample: Sample, snap: String, base: Int = 0) = {
    val idx = ((0 until chainLoop(t)) foldLeft base){case (offset, i) =>
      val next = (chains(t) foldLeft offset){case (start, (signal, width, idx)) =>
        val end = math.min(start + width, snap.length)
        val value = BigInt(snap.substring(start, end), 2)
        signal match {
          case Some(p) if t == ChainType.Trace => 
            // sample addForce Force(p, value)
          case Some(p) if t == ChainType.Regs => 
            sample addCmd Load(p, value, idx)
          case Some(p) if t == ChainType.SRAM && i < idx =>
            sample addCmd Load(p, value, i)
          case Some(p) if t == ChainType.Cntr =>
            sample addCmd Count(p, value)
          case _ =>
        }
        end
      }
      assert(next % daisyWidth == 0)
      next
    }
    // if (t == ChainType.Trace) sample.dumpForces
    idx
  }

  // Generate sample from a string
  def apply(snap: String, cycle: Long) = {
    val sample = new Sample(cycle)
    (ChainType.values.toList filterNot (_ == ChainType.Cntr) foldLeft 0){
      case (base, chainType) => readChain(chainType, sample, snap, base)}
    sample
  }

  // Generate a specific type of sample from a string
  def apply(chainType: ChainType.Value, snap: String, cycle: Long) = {
    val sample = new Sample(cycle)
    readChain(chainType, sample, snap)
    sample
  }

  (io.Source fromFile chainFile).getLines foreach { line =>
    val tokens = line split " "
    assert(tokens.size == 4)
    chains(ChainType(tokens(0).toInt)) += ((tokens(1) match {
      case "null" => None
      case signal => Some(signal) 
    }, tokens(2).toInt, tokens(3).toInt))
  }
}

object Sample {
  // Read samples from a file
  /*
  def load[T <: Module](filename: String, log: java.io.PrintStream = System.out) = {
    val signalMap = HashMap[String, Node]()
    Driver.dfs {
      case node: Delay       => signalMap(node.chiselName) = node
      case node if node.isIo => signalMap(node.chiselName) = node
      case _ =>
    }
    (scala.io.Source.fromFile(filename).getLines foldLeft List[Sample]()){case (samples, line) =>
      val tokens = line split " "
      val cmd = SampleInstType(tokens.head.toInt)
      cmd match {
        case SampleInstType.CYCLE =>
          samples :+ new Sample(tokens.last.toLong)
        case SampleInstType.LOAD =>
          val value = BigInt(tokens.init.last, 16)
          val off = tokens.last.toInt
          (signalMap get tokens.tail.head) match {
            case None => log.println(s"${tokens.tail.head} not found")
            case Some(node) => samples.last addCmd Load(node, value, if (off < 0) None else Some(off))
          }
          samples
        case SampleInstType.FORCE =>
          val value = BigInt(tokens.last, 16)
          (signalMap get tokens.tail.head) match {
            case None => log.println(s"${tokens.tail.head} not found")
            case Some(node) => samples.last addCmd Force(node, value)
          }
          samples
        case SampleInstType.POKE =>
          val node = signalMap(tokens.tail.head) match {case b: Bits => b}
          val value = BigInt(tokens.last, 16)
          samples.last addCmd PokePort(node, value)
          samples
        case SampleInstType.STEP =>
          samples.last addCmd Step(tokens.last.toInt)
          samples
        case SampleInstType.EXPECT =>
          val node = signalMap(tokens.tail.head) match {case b: Bits => b}
          val value = BigInt(tokens.last, 16)
          samples.last addCmd ExpectPort(node, value)
          samples
        case SampleInstType.COUNT => samples // skip
      }
    } sortWith (_.cycle < _.cycle)
  }
  */
}

class Sample(protected[strober] val cycle: Long = -1L) {
  /* private val forceBins = ArrayBuffer[ArrayBuffer[Force]]()
  private var forceBinIdx = 0
  private var forcePrevNode: Option[String] = None
  def addForce(f: Force) {
    val node = transforms.retimingMap getOrElse (f.node, f.node)
    forceBinIdx = forcePrevNode match {
      case Some(p) if p eq node => forceBinIdx + 1 case _ => 0
    }
    if (forceBins.size < forceBinIdx + 1) {
      forceBins += ArrayBuffer[Force]()
    }
    forceBins(forceBinIdx) += f
    forcePrevNode = Some(node)
  }
  def dumpForces {
    forceBins.reverse foreach { bin =>
      cmds ++= bin ; cmds += Step(1) ; bin.clear
    }
    forcePrevNode = None
  } */

  private val cmds = ArrayBuffer[SampleInst]()
  def addCmd(cmd: SampleInst) { cmds += cmd }
      
  def map[T](f: SampleInst => T) = { cmds map f }
  def fold[T](r: T)(f: (T, SampleInst) => T) { (cmds foldLeft r)(f) }
  override def toString = {
    val res = new StringBuilder
    res append "%d cycle: %d\n".format(SampleInstType.CYCLE.id, cycle)
    map {
      case Step(n) =>
        res append "%d %d\n".format(SampleInstType.STEP.id, n)
      case Load(path, value, off) =>
        res append "%d %s %x %d\n".format(SampleInstType.LOAD.id, path, value, off)
      case Force(path, value) =>
        res append "%d %s %x\n".format(SampleInstType.FORCE.id, path, value)
      case PokePort(path, value) =>
        res append "%d %s %x\n".format(SampleInstType.POKE.id, path, value)
      case ExpectPort(path, value) =>
        res append "%d %s %x\n".format(SampleInstType.EXPECT.id, path, value)
      case Count(path, value) =>
        res append "%d %s %d\n".format(SampleInstType.COUNT.id, path, value)
    }
    res.result
  }
}
