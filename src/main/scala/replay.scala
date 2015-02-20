package strober

import Chisel._
import scala.collection.mutable.{HashMap, LinkedHashMap, Queue => ScalaQueue}
import scala.io.Source

class Replay[+T <: Module](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  private val basedir = ensureDir(Driver.targetDir)
  private val signalMap = HashMap[String, Node]()
  private val mem = LinkedHashMap[BigInt, BigInt]()
  private val memrw = ScalaQueue[Boolean]()
  private val memtag = ScalaQueue[BigInt]()
  private val memaddr = ScalaQueue[BigInt]()

  object FAILED extends Exception 

  def pushMemReq(addr: BigInt, tag: BigInt, rw: Boolean) {
    require(memrw.size == memtag.size)
    require(memrw.size == memaddr.size)
    memrw enqueue rw
    memtag enqueue tag
    memaddr enqueue addr 
  }

  def popMemReq = {
    require(memrw.size == memtag.size)
    require(memrw.size == memaddr.size)
    (memaddr.dequeue, memtag.dequeue, memrw.dequeue)
  }

  def hasMemReq = !memrw.isEmpty
  def getMemAddr = memaddr.head
  def isMemReqRead = !memrw.head

  def read(addr: BigInt, tag: BigInt) { 
    pushMemReq(addr, tag, false)
  }

  def write(addr: BigInt, data: BigInt) { 
    mem(addr) = data 
  }

  def loadMem(mem: List[(BigInt, BigInt)]) { }

  def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def loadMem(filename: String, memLen: Int = 32) {
    require(memLen % 8 == 0)
    val blkLen = memLen / 8
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      var write = BigInt(0)
      for (k <- (line.length - 2) to 0 by -2) {
        val addr = base + offset
        val data = BigInt((parseNibble(line(k)) << 4) | parseNibble(line(k+1)))
        write = write | (data << (8 * (addr % blkLen)))
        if (addr % blkLen == blkLen-1) {
          mem(addr - (blkLen-1)) = write
          write = BigInt(0)
        }
        offset += 1
      }
    }
  }

  def run = { }

  def doTest(filename: String) {
    val samples = Sample.load(basedir + "/" + filename)
    try {
      for (sample <- samples) {
        memrw.clear
        memtag.clear
        memaddr.clear
        for (cmd <- sample.cmds) {
          cmd match {
            case Step(n) => step(n)
            case Poke(node, value, off) => pokeBits(signalMap(node), value, off)
            case Expect(node, value) => expect(signalMap(node).toBits, value)
            case Read(addr, tag) => read(addr, tag)
          }
        }
        for ((addr, data) <- sample.mem) {
          write(addr, data)
        }
        loadMem(mem.toList)
        run
      }
    } catch {
      case FAILED =>
    }
  }

  def begin {
    doTest(c.name + ".sample")
  } 

  Driver.dfs { node =>
    if (node.isInObject) signalMap(dumpName(node)) = node
  }
  begin
}
