package Daisy

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}

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

  var hostwidth = 0
  var opwidth = 0
  var addrwidth = 0
  var memwidth = 0
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
      val mask = (1 << (hostwidth-1)) - 1
      for (i <- 0 until ids.size) {
        val shift = (hostwidth-1) * i
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
        x = x << hostwidth | peekMap(ids(ids.size-1-i))
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
    while (peek(dumpName(c.io.host.out.valid)) == 0) {
      takeSteps(1)
    } 
    poke(c.io.host.out.ready, 1)
    val data = peek(c.io.host.out.bits)
    takeSteps(1)
    poke(c.io.host.out.ready, 0)
    data
  }

  def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def pokeSnap {
    if (isTrace) println("Poke Snap")
    // Send POKE command
    poke(SNAP)
    if (isTrace) println("==========")
  }

  def pokeAll {
    if (isTrace) println("Poke All")
    // Send POKE command
    poke(POKE)

    // Send Values
    for (i <- 0 until inputNum) {
      poke(if (pokeMap contains i) (pokeMap(i) << 1) | 1 else BigInt(0))
    }
    pokeMap.clear
    if (isTrace) println("==========")
  }

  def peekAll {
    if (isTrace) println("Peek All")
    peekMap.clear
    // Send PEEK command
    poke(PEEK)

    // Get values
    for (i <- 0 until outputNum) {
      peekMap(i) = peek
    }
    if (isTrace) println("==========")
  }

  def pokeSteps(n: Int) {
    if (isTrace) println("Poke Steps")
    // Send STEP command
    poke((n << opwidth) | STEP)
    if (isTrace) println("==========")
    takeSteps(n)
  }

  def readSnap = {
    if (isTrace) println("Snapshotting")
    val snap = new StringBuilder
    var offset = 0
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      snap append peek.toString(2).reverse.padTo(hostwidth, '0').reverse
    }
    if (isTrace) println("Chain: " + snap.result)
    snap.result
  }

  var begin = false
  def writeSnap(snap: String, n: Int) {
    if (begin) {
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
        val fromSignal = value.toString(2).reverse.padTo(width, '0').reverse
        expect(fromSnap == fromSignal, "Snapshot %s(%s?=%s)".format(signal, fromSnap, fromSignal)) 
        replay append "POKE %s %d\n".format(signal, value)
      } 
      start += width
    }
    begin = true
  }

  // Emulate memory
  private val mem = HashMap[BigInt, BigInt]()
  private var memrw   = false
  private var memtag  = BigInt(0)
  private var memaddr = BigInt(0)
  private var memdata = BigInt(0)
  private var memcycles = -1
  def tickMem {
    println("Tick Memory")
    if (memcycles > 0) {
      poke(c.io.mem.req_cmd.ready, 0)
      poke(c.io.mem.req_data.ready, 0)
      poke(c.io.mem.resp.valid, 0) 
      memcycles -= 1
    } else if (memcycles < 0) {
      if (peek(dumpName(c.io.mem.req_cmd.valid)) == 1) {
        memrw   = if (peek(c.io.mem.req_cmd.bits.rw) == 1) true else false
        memtag  = peek(c.io.mem.req_cmd.bits.tag)
        memaddr = peek(c.io.mem.req_cmd.bits.addr)
        if (!memrw) {
          memcycles = 10
          poke(c.io.mem.req_cmd.ready, 1)
        }
      }
      if (peek(dumpName(c.io.mem.req_data.valid)) == 1) {
        memcycles = 5
        memdata = peek(c.io.mem.req_data.bits.data)
        poke(c.io.mem.req_cmd.ready, 1)
        poke(c.io.mem.req_data.ready, 1)
        for (i <- 0 until (memwidth-1)/8+1) {
          mem(memaddr+i) = (memdata >> (8*i)) & 0xff
        }
      }
    } else {
      if (peek(dumpName(c.io.mem.resp.ready)) == 1) {
        var data = BigInt(0)
        for (i <- 0 until (memwidth-1)/8+1) {
          data |= mem(memaddr+i) << (8*i)
        }
        poke(c.io.mem.resp.bits.data, data)
        poke(c.io.mem.resp.bits.tag, memtag)
        poke(c.io.mem.resp.valid, 1)
      }
      memcycles -= 1
    }
    println("===========")
  }

  def writeMem(addr: BigInt, data: BigInt) {
    poke((1 << opwidth) | MEM)
    val mask = (BigInt(1) << hostwidth) - 1
    for (i <- (addrwidth-1)/hostwidth+1 until 0 by -1) {
      poke(addr >> (hostwidth * (i-1)) & mask)
      tickMem
      takeSteps(1)
    }
    for (i <- (memwidth-1)/hostwidth+1 until 0 by -1) {
      poke(data >> (hostwidth * (i-1)) & mask)
      (data >> (hostwidth * (i-1)) & mask)
      tickMem
      takeSteps(1)
    }
    do {
      tickMem
      takeSteps(1)
    } while (memcycles >= 0)
  }

  def readMem(addr: BigInt) = {
    poke((0 << opwidth) | MEM)
    val mask = (BigInt(1) << hostwidth) - 1
    for (i <- (addrwidth-1)/hostwidth+1 until 0 by -1) {
      poke(addr >> (hostwidth * (i-1)) & mask)
      tickMem
      takeSteps(1)
    }
    do {
      tickMem
      takeSteps(1)
    } while (memcycles >= 0) 
    var data = BigInt(0)
    for (i <- 0 until (memwidth-1)/hostwidth+1) {
      data |= peek << (i * hostwidth)
    }
    data
  }

  override def step(n: Int = 1) {
    val target = t + n
    pokeAll
    if (t > 0) pokeSnap
    pokeSteps(n)
    val snap = if (t > 0) readSnap else ""
    if (isTrace) println("STEP " + n + " -> " + target)
    peekAll
    if (t > 0) writeSnap(snap, n)
    t += n
  }

  def readIoMapFile(filename: String) {
    val filename = targetPrefix + ".io.map"
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    var isInput = false
    for (line <- lines) {
      val tokens = line split " "
      tokens.head match {
        case "HOSTWIDTH:" => hostwidth = tokens.last.toInt
        case "OPWIDTH:"   => opwidth = tokens.last.toInt
        case "ADDRWIDTH:" => addrwidth = tokens.last.toInt
        case "MEMWIDTH:"  => memwidth = tokens.last.toInt
        case "STEP:"      => STEP = tokens.last.toInt
        case "POKE:"      => POKE = tokens.last.toInt
        case "PEEK:"      => PEEK = tokens.last.toInt
        case "SNAP:"      => SNAP = tokens.last.toInt
        case "MEM:"       => MEM = tokens.last.toInt
        case "INPUT:"     => isInput = true
        case "OUTPUT:"    => isInput = false
        case _ => {
          val path = targetPath + (tokens.head stripPrefix targetPrefix)
          val width = tokens.last.toInt
          val n = (width - 1) / (if (isInput) hostwidth - 1 else hostwidth) + 1
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
    // val filename = targetPrefix + ".replay"
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
