package strober

import scala.collection.mutable.ArrayBuffer 

// Enum type for replay commands
object SampleCmd extends Enumeration {
  val FIN, STEP, POKE, EXPECT, WRITE, READ = Value
}

abstract class SampleInst
case class Step(n: Int) extends SampleInst
case class Poke(node: String, value: BigInt, off: Int = -1) extends SampleInst
case class Expect(node: String, value: BigInt) extends SampleInst
case class Write(addr: BigInt, data: BigInt) extends SampleInst
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
    samples += sample
    sample.cmds
  }

  def apply(inst: SampleInst) {
    samples.last.cmds += inst
  }

  def dump = {
    val res = new StringBuilder
    for (sample <- samples) {
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
          case Write(addr, data) =>
            res append "%d %x %08x\n".format(SampleCmd.WRITE.id, addr, data)
          case Read(addr, tag) =>
            res append "%d %x %x\n".format(SampleCmd.READ.id, addr, tag)
        }
      }
      res append "%d\n".format(SampleCmd.FIN.id) 
    }
    res.result
  }

  def load(filename: String) {
    val lines = scala.io.Source.fromFile(filename).getLines
    var sample = new Sample
    for (line <- lines) {
      val tokens = line split " "
      val cmd = tokens.head.toInt
      if (cmd == SampleCmd.STEP.id) {
        val n = tokens.last.toInt
        sample.cmds += Step(n)
      } else if (cmd == SampleCmd.POKE.id) {
        val name = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        name match {
          case MemRegex(name, off) =>
            sample.cmds += Poke(name, value, off.toInt)
          case _ =>
            sample.cmds += Poke(name, value)
        }
      } else if (cmd == SampleCmd.EXPECT.id) {
        val name  = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        sample.cmds += Expect(name, value)
      } else if (cmd == SampleCmd.WRITE.id) {
        val addr = BigInt(tokens.tail.head, 16)
        val data = BigInt(tokens.last, 16)
        sample.cmds += Write(addr, data)
      } else if (cmd == SampleCmd.READ.id) {
        val addr = BigInt(tokens.tail.head, 16)
        val tag  = BigInt(tokens.last, 16)
        sample.cmds += Read(addr, tag)
      } else if (cmd == SampleCmd.FIN.id) {
        samples += sample
        sample = new Sample
      }
    }
    samples += sample
  }
}

class Sample {
  val cmds = ArrayBuffer[SampleInst]()
}
