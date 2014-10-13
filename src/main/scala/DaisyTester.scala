package DebugMachine

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}

abstract class DaisyTester[+T <: DaisyShim[Module]](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val signalMap = HashMap[String, Node]()
  val inputMap = HashMap[String, BigInt]()
  val outputMap = HashMap[String, BigInt]()
  val pokeMap = HashMap[BigInt, BigInt]()
  val peekMap = HashMap[BigInt, BigInt]()
  val stateNames = ArrayBuffer[String]()
  val stateWidths = ArrayBuffer[Int]()
  val snaps = new StringBuilder

  lazy val targetPath = c.target.getPathName(".")
  lazy val targetPrefix = Driver.backend.extractClassName(c.target)
  lazy val basedir = ensureDir(Driver.targetDir)


  override def poke(data: Bits, x: BigInt) {
    if (inputMap contains dumpName(data)) {
      if (isTrace) println("* POKE " + dumpName(data) + " <- " + x + " *")
      pokeMap(inputMap(dumpName(data))) = x
    } else {
      super.poke(data, x)
    }
  }

  override def peek(data: Bits) = {
    if (outputMap contains dumpName(data)) {
      val x = peekMap(outputMap(dumpName(data)))
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


  def pokeAll {
    val pokeOp = c.POKE.getNode.asInstanceOf[Literal].value
    if (isTrace) println("Poke All")
    // Send POKE command
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, pokeOp)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)

    // Send Values
    for (i <- 0 until inputMap.size) {
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
    val peekOp = c.PEEK.getNode.asInstanceOf[Literal].value
    // Send PEEK command
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, peekOp)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)

    // Get values
    for (i <- 0 until outputMap.size) {
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
    val stepOp = c.STEP.getNode.asInstanceOf[Literal].value
    // Send PEEK command
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, (n << c.opwidth) | stepOp)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)
    if (isTrace) println("==========")
  }

  def pokeSnap(addr: Int) {
    if (isTrace) println("Poke Snap(addr : %d)".format(addr))
    val snapOp = c.SNAP.getNode.asInstanceOf[Literal].value
    // Send POKE command
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
    }
    poke(c.io.host.in.bits, (addr << c.opwidth) | snapOp)
    poke(c.io.host.in.valid, 1)
    takeSteps(1)
    poke(c.io.host.in.valid, 0)    
    if (isTrace) println("==========")
  }

  def readDaisyChain(addr: Int) = {
    if (isTrace) println("Read State Daisy Chain")

    // Read daisy chain
    val res = new StringBuilder
    var start = false
    var offset = 0
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      takeSteps(1)
      // Mem request command
      if (peek(dumpName(c.io.mem.req_cmd.valid)) == 1) {
        poke(c.io.mem.req_cmd.ready, 1)
        expect(c.io.mem.req_cmd.bits.addr, addr + offset)
        expect(c.io.mem.req_cmd.bits.rw, 1)
        expect(c.io.mem.req_cmd.bits.tag, 0)
        takeSteps(1)
        poke(c.io.mem.req_cmd.ready, 0)
        offset += (c.memwidth >> 2)
      }
      // Mem request data
      if (peek(dumpName(c.io.mem.req_data.valid)) == 1) {
        poke(c.io.mem.req_data.ready, 1)
        val value = peek(c.io.mem.req_data.bits.data)
        val fromChain = value.toString(2).reverse.padTo(c.memwidth, '0').reverse
        res append fromChain
        takeSteps(1)
        poke(c.io.mem.req_data.ready, 0)
      }
    }
    poke(c.io.mem.req_cmd.ready, 0)
    poke(c.io.mem.req_data.ready, 0)
    if (isTrace) println("Chain: " + res.result)
    res.result
  }

  def verifyDaisyChain(chain: String) {
    val MemRegex = """([\w\.]+)\[(\d+)\]""".r
    var value = BigInt(0)
    var start = 0
    for ((stateName, i) <- stateNames.zipWithIndex) {
      val width = stateWidths(i)
      if (stateName != "null") {
        val path = targetPath + (stateName stripPrefix targetPrefix)
        val value = path match {
          case MemRegex(name, idx) =>
            peek(name, idx.toInt)
          case _ =>
            peek(path)
        } 
        val end = math.min(start + width, chain.length)
        val fromChain = chain.substring(start, end)
        val fromSignal = value.toString(2).reverse.padTo(width, '0').reverse
        expect(fromChain == fromSignal, "Snapshot %s(%s?=%s)".format(stateName, fromChain, fromSignal))
        snaps append "POKE %s %d\n".format(stateName, value)
      } 
      start += width
    }
  }

  def addExpected {
    for (out <- c.outputs) {
      val name = targetPrefix + (dumpName(out) stripPrefix targetPath)
      snaps append "EXPECT %s %d\n".format(name, peek(out))
    }
  }

  def daisyStep(n: Int) {    
    var addr = 0
    for (i <- 0 until (c.addrwidth >> 1)) {
      addr = (addr << 1) | rnd.nextInt(2)
    }
    if (t > 0) pokeSnap(addr)
    pokeSteps(n)
    takeSteps(n)
    if (t > 0) verifyDaisyChain(readDaisyChain(addr))
  }

  override def step(n: Int = 1) {
    val target = t + n
    pokeAll
    daisyStep(n)
    snaps append "STEP %d\n".format(n)
    if (isTrace) println("STEP " + n + " -> " + target)
    peekAll
    addExpected
    t += n
  }

  def readIoMapFile {
    val filename = targetPrefix + ".io.map"
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    var isInput = false
    for (line <- lines) {
      if (line == "INPUT:") {
        isInput = true
      }
      else if (line == "OUTPUT:") {
        isInput = false
      }
      else {
        val tokens = line split " "
        val path = targetPath + (tokens.head stripPrefix targetPrefix)
        if (isInput) 
          inputMap(path) = BigInt(tokens.last)
        else
          outputMap(path) = BigInt(tokens.last)
      }
    }
  }

  def readChainMapFile {
    val filename = targetPrefix + ".chain.map"
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      stateNames += tokens.head
      stateWidths += tokens.last.toInt
    }
  }
 
  override def finish = {
    val targetPrefix = Driver.backend.extractClassName(c.target)
    val filename = targetPrefix + ".snaps"
    val snapfile = createOutputFile(filename)
    try {
      snapfile write snaps.result
    } finally {
      snapfile.close
    }
    super.finish
  }

  Driver.dfs { node =>
    if (node.isInObject) signalMap(dumpName(node)) = node
  }
  readIoMapFile
  readChainMapFile 
}
