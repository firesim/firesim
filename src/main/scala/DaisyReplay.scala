package daisy

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap}
import scala.io.Source

// Enum type for snapshot commands 
object SnapCmd extends Enumeration {
  val FIN, STEP, POKE, EXPECT, WRITE, READ = Value
}

abstract class ReplayCmd
case class Step(n: Int) extends ReplayCmd
case class Poke(node: Node, value: BigInt, off: Int = -1) extends ReplayCmd
case class Expect(node: Bits, value: BigInt) extends ReplayCmd
case class Write(addr: BigInt, data: BigInt) extends ReplayCmd
case class Read(addr: BigInt, tag: BigInt) extends ReplayCmd

class Snapshot {
  val cmds = ArrayBuffer[ReplayCmd]()
}

class DaisyReplay[+T <: Module](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val basedir = ensureDir(Driver.targetDir)
  val snapfile = c.name + ".snap" 
  val snaps = ArrayBuffer[Snapshot]()
  val signalMap = HashMap[String, Node]()

  def loadSnap(filename: String) {
    val MemRegex = """([\w\.]+)\[(\d+)\]""".r
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    var snap = new Snapshot
    for (line <- lines) {
      val tokens = line split " "
      val cmd = tokens.head.toInt
      if (cmd == SnapCmd.STEP.id) {
        val n = tokens.last.toInt
        snap.cmds += Step(n) 
      } else if (cmd == SnapCmd.POKE.id) {
        val name = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        name match {
          case MemRegex(name, off) => 
            snap.cmds += Poke(signalMap(name), value, off.toInt)
          case _ =>
            snap.cmds += Poke(signalMap(name), value)
        }
      } else if (cmd == SnapCmd.EXPECT.id) {
        val name = tokens.tail.head
        val value = BigInt(tokens.last, 16)
        snap.cmds += Expect(signalMap(name).toBits, value)
      } else if (cmd == SnapCmd.WRITE.id) {
        val addr = BigInt(tokens.tail.head, 16)
        val data = BigInt(tokens.last, 16)
        snap.cmds += Write(addr, data)
      } else if (cmd == SnapCmd.READ.id) {
        val addr = BigInt(tokens.tail.head, 16)
        val tag = BigInt(tokens.last, 16)
        snap.cmds += Read(addr, tag)
      } else if (cmd == SnapCmd.FIN.id) {
        snaps += snap
        snap = new Snapshot
      }
    }
    snaps += snap
  }
 
  def read(addr: BigInt, tag: BigInt) { }

  def write(addr: BigInt, data: BigInt) { }

  def run = { }
  
  Driver.dfs { node =>
    if (node.isInObject) signalMap(dumpName(node)) = node
  }

  loadSnap(snapfile)
  for (snap <- snaps) {
    for (cmd <- snap.cmds) {
      cmd match {
        case Step(n) => step(n)
        case Poke(node, value, off) => pokeBits(node, value, off)
        case Expect(node, value) => expect(node, value)
        case Write(addr, data) => write(addr, data)
        case Read(addr, tag) => read(addr, tag)
      }
    }
    run
  }
}
