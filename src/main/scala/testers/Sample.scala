package strober
package testers

import scala.collection.mutable.{ArrayBuffer, HashMap}

// Enum type for replay commands
object SampleInstType extends Enumeration {
  val CYCLE, LOAD, FORCE, POKE, STEP, EXPECT, COUNT = Value
}

sealed abstract class SampleInst
case class Step(n: Int) extends SampleInst
case class Load(node: String, value: BigInt, off: Int) extends SampleInst
case class Force(node: String, value: BigInt) extends SampleInst
case class PokePort(node: String, value: BigInt) extends SampleInst
case class ExpectPort(node: String, value: BigInt) extends SampleInst
case class Count(node: String, value: BigInt) extends SampleInst

private[testers] object DaisyChainReader {
  type ChainMap = Map[ChainType.Value, Seq[(Option[String], Int, Int)]]
  type ChainLoop = Map[ChainType.Value, Int]
  type ChainLen = Map[ChainType.Value, Int]
  def apply(chainFile: java.io.File, daisyWidth: Int) = {
    type ChainInfo = ArrayBuffer[(Option[String], Int, Int)]
    val chains = (ChainType.values.toList map (_ -> new ChainInfo)).toMap
    val chainLoop = HashMap((ChainType.values.toList map (_ -> 0)):_*)
    val chainLen = HashMap((ChainType.values.toList map (_ -> 0)):_*)
    if (chainFile.exists) {
      (io.Source fromFile chainFile).getLines foreach { line =>
        val tokens = line split " "
        assert(tokens.size == 4)
        val chainType = ChainType(tokens.head.toInt)
        val signal = tokens(1) match { case "null" => None case p => Some(p) }
        val width = tokens(2).toInt
        val depth = tokens(3).toInt
        chains(chainType) += ((signal, width, depth))
        chainLen(chainType) += width
        chainLoop(chainType) =
          if (chainType == ChainType.SRAM) chainLoop(chainType) max depth else 1
      }
    }
    (new DaisyChainReader(chains map { case (k, v) => k -> v.toSeq }, chainLoop.toMap, daisyWidth),
     chainLoop.toMap, chainLen.toMap map { case (k, v) => k -> (v / daisyWidth) })
  }
}

private class DaisyChainReader(
    chains: DaisyChainReader.ChainMap,
    chainLoop: DaisyChainReader.ChainLoop,
    daisyWidth: Int) {
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
}

object Sample {
  // Read samples from a file
  def apply[T <: chisel3.Module](file: java.io.File) = ((scala.io.Source fromFile 
      file).getLines foldLeft List[Sample]()){case (samples, line) =>
    val tokens = line split " "
    SampleInstType(tokens.head.toInt) match {
      case SampleInstType.CYCLE =>
        samples :+ new Sample(tokens.last.toLong)
      case SampleInstType.LOAD =>
        val node = tokens.tail.head
        val value = BigInt(tokens.init.last, 16)
        val off = tokens.last.toInt
        samples.last addCmd Load(node, value, off)
        samples
      case SampleInstType.FORCE =>
        val node = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        samples.last addCmd Force(node, value)
        samples
      case SampleInstType.POKE =>
        val node = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        samples.last addCmd PokePort(node, value)
        samples
      case SampleInstType.STEP =>
        samples.last addCmd Step(tokens.last.toInt)
        samples
      case SampleInstType.EXPECT =>
        val node = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        samples.last addCmd ExpectPort(node, value)
        samples
      case SampleInstType.COUNT => samples // skip
    }
  } sortWith (_.cycle < _.cycle)
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
