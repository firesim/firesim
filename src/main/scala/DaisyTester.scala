package faee

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}

abstract class DaisyTester[+T <: DaisyWrapper[Module]](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val signalMap = HashMap[String, Node]()
  val inputMap = HashMap[String, BigInt]()
  val outputMap = HashMap[String, BigInt]()
  val pokeMap = HashMap[BigInt, BigInt]()
  val peekMap = HashMap[BigInt, BigInt]()
  val stateNames = ArrayBuffer[String]()
  val stateDumpNames = ArrayBuffer[String]()
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
    while (peek(c.io.hostIn.ready) == 0) {
      takeSteps(1)
    }
    poke(c.io.hostIn.bits, pokeOp)
    poke(c.io.hostIn.valid, 1)
    takeSteps(1)
    poke(c.io.hostIn.valid, 0)

    // Send Values
    for (i <- 0 until inputMap.size) {
      val in = if (pokeMap contains i) (pokeMap(i) << 1) | 1 else BigInt(0)
      while (peek(c.io.hostIn.ready) == 0) {
        takeSteps(1)
      }
      poke(c.io.hostIn.bits, in)
      poke(c.io.hostIn.valid, 1)
      takeSteps(1)
      poke(c.io.hostIn.valid, 0)
    }
    pokeMap.clear
    if (isTrace) println("==========")
  }

  def peekAll {
    if (isTrace) println("Peek All")
    val peekOp = c.PEEK.getNode.asInstanceOf[Literal].value
    // Send PEEK command
    while (peek(c.io.hostIn.ready) == 0) {
      takeSteps(1)
    }
    poke(c.io.hostIn.bits, peekOp)
    poke(c.io.hostIn.valid, 1)
    takeSteps(1)
    poke(c.io.hostIn.valid, 0)

    // Get values
    for (i <- 0 until outputMap.size) {
      poke(c.io.hostOut.ready, 1)
      while (peek(c.io.hostOut.valid) == 0) {
        takeSteps(1)
      } 
      peekMap(i) = peek(c.io.hostOut.bits)
      takeSteps(1)
      poke(c.io.hostOut.ready, 0)
    }
    if (isTrace) println("==========")
  }

  def pokeSteps(n: Int) {
    if (isTrace) println("Poke Steps")
    val stepOp = c.STEP.getNode.asInstanceOf[Literal].value
    // Send PEEK command
    while (peek(c.io.hostIn.ready) == 0) {
      takeSteps(1)
    }
    poke(c.io.hostIn.bits, (n << c.opwidth) | stepOp)
    poke(c.io.hostIn.valid, 1)
    takeSteps(1)
    poke(c.io.hostIn.valid, 0)
    if (isTrace) println("==========")
  }


  /*
  override def expect(data: Bits, expected: BigInt) = {
    if (ioMap contains data) {
      super.expect(ioMap(data), expected)
    } else {
      super.expect(data, expected)
    }
  }
  */

  def binToDec(bin: String) = {
    var res = BigInt(0)
    for (c <- bin) {
      c match {
        case '0' => res = res << 1
        case '1' => res = res << 1 | 1
      }
    }
    res
  }

  /*
  def addExpects {
    val targetPath = c.target.getPathName(".")
    val targetPrefix = Driver.backend.extractClassName(c.target)
    for (out <- outs) {
      val name = targetPrefix + (dumpName(out) stripPrefix targetPath)
      val expected = peek(dumpName(ioMap(out)))
      val expect = "EXPECT %s %d\n".format(name, expected)
      snaps append expect  
    }
  }
  */

  def daisyStep(n: Int) {
    pokeSteps(n)
    takeSteps(n)
    /*
    do {
      takeSteps(1)
    } while (peek(c.io.stall) == 0) 
    */
  }

  /*
  def readDaisyChain(out: DecoupledIO[UInt]) = {
    val chain = new StringBuilder

    if (isTrace) println("Read Daisy Chain")
    poke(out.ready, 1)
    while(peek(dumpName(out.valid)) == 0) {
      takeSteps(1)
    }

    var count = 0
    do {
      val bit = peek(dumpName(out.bits))
      chain append "%d".format(bit)
      takeSteps(1)
      count += 1
    } while(peek(dumpName(out.valid)) == 1)
    poke(out.ready, 0)
    if (isTrace) println("Read Count = %d".format(count))

    chain.result
  }
  */
  
  def verifyDaisyChain(chain: String) {
    val MemRegex = """([\w\.]+)\[(\d+)\]""".r
    val targetPath = c.target.getPathName(".")
    val targetPrefix = Driver.backend.extractClassName(c.target)
    val values = ArrayBuffer[BigInt]()
    values ++= stateNames map {x => 
      (targetPath + (x stripPrefix targetPrefix))  match {
      case MemRegex(name, idx) => 
        peek(name, idx.toInt)
      case name => 
        peek(name)
    } }

    var start = 0
    for ((width, i) <- stateWidths.zipWithIndex) {
      val end = math.min(start + width, chain.length)
      val value = if (start < end) binToDec(chain.substring(start, end)) else BigInt(0)
      val poke = "POKE %s %d\n".format(stateNames(i), value)
      expect(value == values(i), stateNames(i))
      snaps append poke
      start += width
    }
  }

  override def step(n: Int = 1) {
    val target = t + n
    // if (t > 0) addExpects
    pokeAll
    daisyStep(n)
    if (isTrace) println("STEP " + n + " -> " + target)
    peekAll
    // val chain = readDaisyChain(c.io.stateOut)
    // verifyDaisyChain(chain)
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

  /*
  def readStateMapFile {
    val filename = targetPrefix + ".state.map"
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      stateNames += tokens.head
      stateWidths += tokens.last.toInt 
    }
  }
  */
 
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
  // readChainFile 
}
