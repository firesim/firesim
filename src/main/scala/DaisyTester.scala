package faee

import Chisel._
import scala.collection.mutable.ArrayBuffer

abstract class DaisyTester[+T <: DaisyWrapper[Module]](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val ioMap = DaisyBackend.ioMap
  val stateNames = ArrayBuffer[String]()
  val stateWidths = ArrayBuffer[Int]()

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
    val cmd = "wire_peek " + name
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
    val values = ArrayBuffer[BigInt]()
    values ++= stateNames map (peek _)

    var start = 0
    for ((width, i) <- stateWidths.zipWithIndex) {
      val end = math.min(start + width, chain.length)
      val value = values(i).toString(2).reverse.padTo(width, '0').reverse
      expect(start < end && value == chain.substring(start, end), stateNames(i))
      // println("%s %s".format(value, chain.substring(start, end)))
      start += width
    }
  }

  def daisyStep(n: Int) {
    pokeSteps(n)
    do {
      takeSteps(1)
    } while (peek(c.io.stall) == 0) 
  }

  override def step(n: Int = 1) {
    val target = t + n
    daisyStep(n)
    if (isTrace) println("STEP " + n + " -> " + target)
    val chain = readDaisyChain(c.io.stateOut)
    verifyDaisyChain(chain)
    t += n
  }

  def readChainFile {
    val targetPath = c.target.getPathName(".")
    val targetPrefix = Driver.backend.extractClassName(c.target)
    val filename = targetPrefix + ".state.chain"
    val basedir = ensureDir(Driver.targetDir)
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      stateNames += (targetPath + (tokens.head stripPrefix targetPrefix))
      stateWidths += tokens.last.toInt 
    }
  }

  readChainFile 
}
