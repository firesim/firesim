package Daisy

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.io.Source

abstract class DaisyTester[+T <: DaisyShim[Module]](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val signalMap = HashMap[String, Node]()
  val inputMap = HashMap[String, ArrayBuffer[BigInt]]()
  val outputMap = HashMap[String, ArrayBuffer[BigInt]]()
  val pokeMap = HashMap[BigInt, BigInt]()
  val peekMap = HashMap[BigInt, BigInt]()
  val signals = ArrayBuffer[String]()
  val widths = ArrayBuffer[Int]()
  val replay = new StringBuilder
  var snapfilename = ""

  lazy val targetPath = c.target.getPathName(".")
  lazy val targetPrefix = Driver.backend.extractClassName(c.target)
  lazy val basedir = ensureDir(Driver.targetDir)

  var hostLen = 0
  var cmdLen = 0
  var addrLen = 0
  var memLen = 0
  var STEP = -1
  var POKE = -1
  var PEEK = -1
  var SNAP = -1
  var MEM = -1
  var inputNum = 0
  var outputNum = 0

  override def poke(data: Bits, x: BigInt) {
    if (inputMap contains dumpName(data)) {
      if (isTrace) println("* POKE " + dumpName(data) + " <- " + x + " *")
      val ids = inputMap(dumpName(data))
      val mask = (BigInt(1) << hostLen) - 1
      for (i <- 0 until ids.size) {
        val shift = hostLen * i
        val data = (x >> shift) & mask 
        pokeMap(ids(ids.size-1-i)) = data
      }
    } else {
      super.poke(data, x)
    }
  }

  override def peek(data: Bits) = {
    if (outputMap contains dumpName(data)) {
      var x = BigInt(0)
      val ids = outputMap(dumpName(data))
      for (i <- 0 until ids.size) {
        x = x << hostLen | peekMap(ids(ids.size-1-i))
      }
      if (isTrace) println("* PEEK " + dumpName(data) + " <- " + x + " *")
      x
    } else {
      super.peek(data)
    }
  }

  def peek(name: String) = {
    val cmd = "wire_peek %s".format(name)
    Literal.toLitVal(emulatorCmd(cmd))
  }

  def peek(name: String, off: Int) = {
    val cmd = "mem_peek %s %d".format(name, off)
    Literal.toLitVal(emulatorCmd(cmd))
  }

  def poke(data: BigInt) {
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, data)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)
  }

  def peek: BigInt = {
    poke(c.io.host.out.ready, 1)
    while (peek(dumpName(c.io.host.out.valid)) == 0) {
      takeSteps(1)
    } 
    val data = peek(c.io.host.out.bits)
    takeSteps(1)
    poke(c.io.host.out.ready, 0)
    data
  }

  def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def stayIdle(n: Int = 2) {
    // the mathine is idle in realistic cases
    takeSteps(n) 
  }

  def pokeSnap {
    if (isTrace) println("Poke Snap")
    stayIdle()
    // Send SNAP command
    poke(SNAP)
    stayIdle()
    if (isTrace) println("==========")
  }

  def pokeAll {
    if (isTrace) println("Poke All")
    // Send POKE command
    stayIdle()
    poke(POKE)

    // Send Values
    for (i <- 0 until inputNum) {
      stayIdle()
      poke(pokeMap getOrElse (i, BigInt(0)))
    }
    stayIdle()
    if (isTrace) println("==========")
  }

  def peekAll {
    if (isTrace) println("Peek All")
    stayIdle()
    peekMap.clear
    // Send PEEK command
    poke(PEEK)
    // Get values
    for (i <- 0 until outputNum) {
      stayIdle()
      peekMap(i) = peek
    }
    stayIdle()
    if (isTrace) println("==========")
  }

  def pokeSteps(n: Int) {
    if (isTrace) println("Poke Steps")
    // Send STEP command
    poke((n << cmdLen) | STEP)
    if (isTrace) println("==========")
  }

  private def intToBin(value: BigInt, size: Int) = {
    var bin = ""
    for (i <- 0 until size) {
      bin += (((value >> (size-1-i)) & 0x1) + '0').toChar
    }
    bin
  }

  def readSnap = {
    if (isTrace) println("Snapshotting")
    val snap = new StringBuilder
    var offset = 0
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      snap append intToBin(peek, hostLen) 
    }
    if (isTrace) println("Chain: " + snap.result)
    snap.result
  }

  var beginSnap = false
  def writeSnap(snap: String, n: Int) {
    if (beginSnap) {
      replay append "STEP %d\n".format(n)
      for (out <- c.outputs) {
        val name = targetPrefix + (dumpName(out) stripPrefix targetPath)
        replay append "EXPECT %s %d\n".format(name, peek(out))
      }
    }

    val MemRegex = """([\w\.]+)\[(\d+)\]""".r
    var start = 0
    for ((signal, i) <- signals.zipWithIndex) {
      val width = widths(i)
      if (signal != "null") {
        val path = targetPath + (signal stripPrefix targetPrefix)
        val value = path match {
          case MemRegex(name, idx) =>
            peek(name, idx.toInt)
          case _ =>
            peek(path)
        }
        val end = math.min(start + width, snap.length)
        val fromSnap = snap.substring(start, end)
        val fromSignal = intToBin(value, width) 
        expect(fromSnap == fromSignal, "Snapshot %s(%s?=%s)".format(signal, fromSnap, fromSignal)) 
        replay append "POKE %s %d\n".format(signal, value)
      } 
      start += width
    }
    beginSnap = true
  }

  // Emulate AXI Slave
  private val mem = HashMap[BigInt, BigInt]()
  private var memrw   = false
  private var memtag  = BigInt(0)
  private var memaddr = BigInt(0)
  private var memdata = BigInt(0)
  private var memcycles = -1
  def tickMem {
    def read(addr: BigInt) = {
      var data = BigInt(0)
      for (i <- 0 until (memLen-1)/8+1) {
        data |= mem(memaddr+i) << (8*i)
      }
      data
    }
    def write(addr: BigInt, data: BigInt) {
      for (i <- 0 until (memLen-1)/8+1) {
        mem(addr+i) = (data >> (8*i)) & 0xff
      }
    }
    if (isTrace) println("Tick Memory")
    if (memcycles > 0) {
      poke(c.io.mem.req_cmd.ready, 0)
      poke(c.io.mem.req_data.ready, 0)
      poke(c.io.mem.resp.valid, 0) 
      memcycles -= 1
    } else if (memcycles < 0) {
      poke(c.io.mem.resp.valid, 0)
      if (peek(dumpName(c.io.mem.req_cmd.valid)) == 1) {
        memrw   = if (peek(c.io.mem.req_cmd.bits.rw) == 1) true else false
        memtag  = peek(c.io.mem.req_cmd.bits.tag)
        memaddr = peek(c.io.mem.req_cmd.bits.addr)
        if (!memrw) {
          memcycles = 2
          poke(c.io.mem.req_cmd.ready, 1)
        }
      }
      if (peek(dumpName(c.io.mem.req_data.valid)) == 1) {
        val data = peek(c.io.mem.req_data.bits.data)
        poke(c.io.mem.req_cmd.ready, 1)
        poke(c.io.mem.req_data.ready, 1)
        write(memaddr, data)
        memcycles = 1
      }
    } else {
      if (!memrw) {
        val data = read(memaddr)
        poke(c.io.mem.resp.bits.data, data)
        poke(c.io.mem.resp.bits.tag, memtag)
        poke(c.io.mem.resp.valid, 1)
      }
      memcycles -= 1
    }
    takeSteps(1)
    poke(c.io.mem.req_cmd.ready, 0)
    poke(c.io.mem.req_data.ready, 0)
    poke(c.io.mem.resp.valid, 0) 
    if (isTrace) println("===========")
  }

  def readMem(addr: BigInt) = {
    poke((0 << cmdLen) | MEM)
    val mask = (BigInt(1) << hostLen) - 1
    for (i <- (addrLen-1)/hostLen+1 until 0 by -1) {
      poke(addr >> (hostLen * (i-1)) & mask)
      tickMem
    }
    do {
      tickMem
    } while (memcycles >= 0) 
    var data = BigInt(0)
    for (i <- 0 until (memLen-1)/hostLen+1) {
      data |= peek << (i * hostLen)
    }
    data
  }

  def writeMem(addr: BigInt, data: BigInt) {
    poke((1 << cmdLen) | MEM)
    val mask = (BigInt(1) << hostLen) - 1
    for (i <- (addrLen-1)/hostLen+1 until 0 by -1) {
      poke(addr >> (hostLen * (i-1)) & mask)
      tickMem
    }
    for (i <- (memLen-1)/hostLen+1 until 0 by -1) {
      poke(data >> (hostLen * (i-1)) & mask)
      (data >> (hostLen * (i-1)) & mask)
      tickMem
    }
    do {
      tickMem
    } while (memcycles >= 0)
  }

  def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def slowLoadMem(filename: String) {
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      for (k <- (line.length - 8) to 0 by -8) {
        val data = 
          BigInt((parseNibble(line(k)) << 28) | (parseNibble(line(k+1)) << 24)) |
          BigInt((parseNibble(line(k+2)) << 20) | (parseNibble(line(k+3)) << 16)) |
          BigInt((parseNibble(line(k+4)) << 12) | (parseNibble(line(k+5)) << 8)) |
          BigInt((parseNibble(line(k+6)) << 4) | parseNibble(line(k+7)))
        writeMem(base+offset, data)
        offset += 4
      }
    }
  }

  def fastLoadMem(filename: String) {
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      for (k <- (line.length - 2) to 0 by -2) {
        val data = BigInt((parseNibble(line(k)) << 4) | parseNibble(line(k+1)))
        mem(base+offset) = data
        offset += 1
      }
    }
  }

  override def step(n: Int = 1) {
    val target = t + n
    if (isTrace) println("STEP " + n + " -> " + target)
    pokeAll
    pokeSnap
    pokeSteps(n)
    for (i <- 0 until n) {
      tickMem
    }
    val snap = readSnap 
    peekAll
    writeSnap(snap, n)
    t += n
  }

  def readIoMapFile(filename: String) {
    val filename = targetPrefix + ".io.map"
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    var isInput = false
    for (line <- lines) {
      val tokens = line split " "
      tokens.head match {
        case "HOSTLEN:" => hostLen = tokens.last.toInt
        case "ADDRLEN:" => addrLen = tokens.last.toInt
        case "MEMLEN:"  => memLen = tokens.last.toInt
        case "CMDLEN:"  => cmdLen = tokens.last.toInt
        case "STEP:"    => STEP = tokens.last.toInt
        case "POKE:"    => POKE = tokens.last.toInt
        case "PEEK:"    => PEEK = tokens.last.toInt
        case "SNAP:"    => SNAP = tokens.last.toInt
        case "MEM:"     => MEM = tokens.last.toInt
        case "INPUT:"   => isInput = true
        case "OUTPUT:"  => isInput = false
        case _ => {
          val path = targetPath + (tokens.head stripPrefix targetPrefix)
          val width = tokens.last.toInt
          val n = (width - 1) / hostLen + 1
          if (isInput) {
            inputMap(path) = ArrayBuffer[BigInt]()
            for (i <- 0 until n) {
              inputMap(path) += BigInt(inputNum)
              inputNum += 1
            }
          } else {
            outputMap(path) = ArrayBuffer[BigInt]()
            for (i <- 0 until n) {
              outputMap(path) += BigInt(outputNum)
              outputNum += 1
            }
          }
        }
      }
    }
  }

  def readChainMapFile(filename: String) {
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      signals += tokens.head
      widths += tokens.last.toInt
    }
  }
 
  override def finish = {
    val snapfile = createOutputFile(snapfilename)
    try {
      snapfile write replay.result
    } finally {
      snapfile.close
    }
    super.finish
  }

  Driver.dfs { node =>
    if (node.isInObject) signalMap(dumpName(node)) = node
  }
  readIoMapFile(targetPrefix + ".io.map")
  readChainMapFile(targetPrefix + ".chain.map")
  snapfilename = targetPrefix + ".replay"
}
