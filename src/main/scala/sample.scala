package strober

import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

// Enum type for replay commands
object SampleCmd extends Enumeration {
  val FIN, STEP, POKE, EXPECT, READ, WRITE = Value
}

abstract class SampleInst
case class Step(n: Int) extends SampleInst
case class Poke(node: String, value: BigInt, off: Int = -1) extends SampleInst
case class Expect(node: String, value: BigInt) extends SampleInst
case class Read(addr: BigInt, tag: BigInt) extends SampleInst

object Sample {
  val samples = ArrayBuffer[Sample]()
  val signals = ArrayBuffer[String]()
  val widths  = ArrayBuffer[Int]()
  val MemRegex = """([\w\.]+)\[(\d+)\]""".r

  def addMapping(signal: String, width: Int) {
    signals += signal
    widths  += width
  }

  def apply(snap: String) = {
    val sample = new Sample
    var start = 0
    for ((signal, i) <- signals.zipWithIndex) {
      val width = widths(i)
      if (signal != "null") {
        val end = math.min(start + width, snap.length)
        val value = BigInt(snap.substring(start, end), 2)
        signal match {
          case MemRegex(name, off) =>
            sample.cmds += Poke(name, value, off.toInt)
          case _ =>
            sample.cmds += Poke(signal, value)
        }
      }
      start += width
    }
    sample
  }

  def dump(samples: List[Sample]) = {
    val res = new StringBuilder
    for ((sample, i) <- samples.zipWithIndex) {
      res append "99 Sample#%d\n".format(i)
      for (cmd <- sample.cmds) {
        cmd match {
          case Poke(node, value, off) => 
            if (off == -1) {
              res append "%d %s %x\n".format(SampleCmd.POKE.id, node, value)
            } else {
              res append "%d %s[%d] %x\n".format(SampleCmd.POKE.id, node, off, value)
            }
          case Expect(node, value) =>
            res append "%d %s %x\n".format(SampleCmd.EXPECT.id, node, value)
          case Step(n) =>
            res append "%d %d\n".format(SampleCmd.STEP.id, n)
          case Read(addr, tag) =>
            res append "%d %x %x\n".format(SampleCmd.READ.id, addr, tag)
        }
      }
      for ((addr, data) <- sample.mem) {
        res append "%d %x %08x\n".format(SampleCmd.WRITE.id, addr, data)
      }
      res append "%d\n".format(SampleCmd.FIN.id) 
    }
    res.result
  }

  def load(filename: String) = {
    val samples = ArrayBuffer[Sample](new Sample)
    val lines = scala.io.Source.fromFile(filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      val cmd = tokens.head.toInt
      if (cmd == SampleCmd.STEP.id) {
        val n = tokens.last.toInt
        samples.last.cmds += Step(n)
      } else if (cmd == SampleCmd.POKE.id) {
        val name = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        name match {
          case MemRegex(name, off) =>
            samples.last.cmds += Poke(name, value, off.toInt)
          case _ =>
            samples.last.cmds += Poke(name, value)
        }
      } else if (cmd == SampleCmd.EXPECT.id) {
        val name  = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        samples.last.cmds += Expect(name, value)
      } else if (cmd == SampleCmd.READ.id) {
        val addr = BigInt(tokens.tail.head, 16)
        val tag  = BigInt(tokens.last, 16)
        samples.last.cmds += Read(addr, tag)
      } else if (cmd == SampleCmd.WRITE.id) {
        val addr = BigInt(tokens.tail.head, 16)
        val data = BigInt(tokens.last, 16)
        samples.last.mem(addr) = data
      } else if (cmd == SampleCmd.FIN.id) {
        samples += new Sample
      }
    }
    samples.toList
  }
}

class Sample {
  val cmds = ArrayBuffer[SampleInst]()
  val mem  = LinkedHashMap[BigInt, BigInt]()
}
