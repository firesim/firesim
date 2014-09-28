package faee

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap}

abstract class DaisyTester[+T <: DaisyWrapper[Module]](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val ioMap = DaisyBackend.ioMap
  val signalMap = HashMap[String, Node]()
  val stateNames = ArrayBuffer[String]()
  val stateDumpNames = ArrayBuffer[String]()
  val stateWidths = ArrayBuffer[Int]()
  val snaps = new StringBuilder
  val outs = for ((n, io) <- c.target.wires ; if io.dir == OUTPUT && (ioMap contains io)) yield io

  override def poke(data: Bits, x: BigInt) {
    if (ioMap contains data) {
      super.poke(ioMap(data), x)
    } else {
      super.poke(data, x)
    }
  }

  override def peek(data: Bits) = {
    if (ioMap contains data) {
      super.peek(ioMap(data))
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

  override def expect(data: Bits, expected: BigInt) = {
    if (ioMap contains data) {
      super.expect(ioMap(data), expected)
    } else {
      super.expect(data, expected)
    }
  }

  def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def pokeSteps(n: Int) {
    if (isTrace) println("Poke Steps")
    while(peek(c.io.stepsIn.ready) == 0)
      takeSteps(1)
    poke(c.io.stepsIn.bits, n)
    poke(c.io.stepsIn.valid, 1)
    takeSteps(1)
    poke(c.io.stepsIn.valid, 0)
    if (isTrace) println("==========")
  }

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

  def daisyStep(n: Int) {
    pokeSteps(n)
    do {
      takeSteps(1)
    } while (peek(c.io.stall) == 0) 
  }

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
    snaps append ("STEP %d\n".format(n))
    if (t > 0) addExpects
    daisyStep(n)
    if (isTrace) println("STEP " + n + " -> " + target)
    val chain = readDaisyChain(c.io.stateOut)
    verifyDaisyChain(chain)
    t += n
  }

  def readChainFile {
    val targetPrefix = Driver.backend.extractClassName(c.target)
    val filename = targetPrefix + ".state.chain"
    val basedir = ensureDir(Driver.targetDir)
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
  readChainFile 
}
