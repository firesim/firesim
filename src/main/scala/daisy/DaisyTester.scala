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

  lazy val targetPath = c.target.getPathName(".")
  lazy val targetPrefix = Driver.backend.extractClassName(c.target)
  lazy val basedir = ensureDir(Driver.targetDir)

  var hostwidth = 0
  var opwidth = 0
  var STEP = -1
  var POKE = -1
  var PEEK = -1
  var SNAP = -1
  var inputNum = 0
  var outputNum = 0

  override def poke(data: Bits, x: BigInt) {
    if (inputMap contains dumpName(data)) {
      if (isTrace) println("* POKE " + dumpName(data) + " <- " + x + " *")
      val ids = inputMap(dumpName(data))
      for (i <- 0 until ids.size) {
        val mask = (x >> (i * (hostwidth - 1))) & ((1 << (hostwidth - 1)) - 1)
        pokeMap(ids(ids.size-1-i)) = mask
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

  def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def pokeSnap {
    if (isTrace) println("Poke Snap")
    // Send POKE command
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, SNAP)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)    
    if (isTrace) println("==========")
  }

  def pokeAll {
    if (isTrace) println("Poke All")
    // Send POKE command
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, POKE)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)

    // Send Values
    for (i <- 0 until inputNum) {
      val in = if (pokeMap contains i) (pokeMap(i) << 1) | 1 else BigInt(0)
      while (peek(dumpName(c.io.host.in.ready)) == 0) {
        takeSteps(1)
      }
      poke(c.io.host.in.bits, in)
      poke(c.io.host.in.valid, 1)
      takeSteps(1)
      poke(c.io.host.in.valid, 0)
    }
    pokeMap.clear
    if (isTrace) println("==========")
  }

  def peekAll {
    if (isTrace) println("Peek All")
    peekMap.clear
    // Send PEEK command
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, PEEK)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)

    // Get values
    for (i <- 0 until outputNum) {
      poke(c.io.host.out.ready, 1)
      while (peek(dumpName(c.io.host.out.valid)) == 0) {
        takeSteps(1)
      }
      peekMap(i) = peek(c.io.host.out.bits)
      takeSteps(1)
      poke(c.io.host.out.ready, 0)
    }
    if (isTrace) println("==========")
  }

  def pokeSteps(n: Int) {
    if (isTrace) println("Poke Steps")
    // Send STEP command
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, (n << opwidth) | STEP)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)
    if (isTrace) println("==========")
    takeSteps(n)
  }

  def snapshot = {
    if (isTrace) println("Snapshotting")
    val snap = new StringBuilder
    var offset = 0
    poke(c.io.host.out.ready, 1)
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
      if (peek(dumpName(c.io.host.out.valid)) == 1) {
        val value = peek(c.io.host.out.bits)
        snap append value.toString(2).reverse.padTo(hostwidth, '0').reverse
      }
    }
    poke(c.io.host.out.ready, 0)
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

  override def step(n: Int = 1) {
    val target = t + n
    pokeAll
    if (t > 0) pokeSnap
    pokeSteps(n)
    val snap = if (t > 0) snapshot else ""
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
        case "STEP:"      => STEP = tokens.last.toInt
        case "POKE:"      => POKE = tokens.last.toInt
        case "PEEK:"      => PEEK = tokens.last.toInt
        case "SNAP:"      => SNAP = tokens.last.toInt
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
    val filename = targetPrefix + ".replay"
    val snapfile = createOutputFile(filename)
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
}
