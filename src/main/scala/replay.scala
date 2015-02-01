package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap}
import scala.io.Source

class Replay[+T <: Module](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val basedir = ensureDir(Driver.targetDir)
  val samplefile = c.name + ".sample" 
  private val mem = HashMap[BigInt, BigInt]()
  private val signalMap = HashMap[String, Node]()

  object FAILED extends Exception 

  def read(addr: BigInt, tag: BigInt) { }

  def write(addr: BigInt, data: BigInt) { mem(addr) = data }

  def loadMem(mem: List[(BigInt, BigInt)]) = { }

  def run = { }
 
  Driver.dfs { node =>
    if (node.isInObject) signalMap(dumpName(node)) = node
  }
  Sample.load(basedir + "/" + samplefile)
  try {
    for (sample <- Sample.samples) {
      for (cmd <- sample.cmds) {
        cmd match {
          case Step(n) => step(n)
          case Poke(node, value, off) => pokeBits(signalMap(node), value, off)
          case Expect(node, value) => expect(signalMap(node).toBits, value)
          case Read(addr, tag) => read(addr, tag)
          case Write(addr, data) => write(addr, data)
        }
      }
      loadMem(mem.toList)
      run
    }
  } catch {
    case FAILED =>
  }
}
