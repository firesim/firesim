package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, Queue => ScalaQueue}
import scala.io.Source

abstract class StroberTester[+T <: Strober[Module]](c: T, isTrace: Boolean = true, sampleCheck : Boolean = true) extends Tester(c, isTrace) {
  val inMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val outMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val pokeMap = HashMap[BigInt, BigInt]()
  val peekMap = HashMap[BigInt, BigInt]()

  val targetPath = c.target.getPathName(".")
  val targetPrefix = Driver.backend.extractClassName(c.target)
  val basedir = ensureDir(Driver.targetDir)

  val hostLen = c.hostLen
  val addrLen = c.addrLen
  val tagLen = c.tagLen
  val memLen = c.memLen
  val cmdLen = c.cmdLen
  var snapLen = 0

  var inNum = 0
  var outNum = 0

  val samples = ArrayBuffer[Sample]()
  var sampleNum = 200
  var stepSize = 1
  var prefix = ""

  import Cmd._

  override def poke(data: Bits, x: BigInt) {
    if (inMap contains dumpName(data)) {
      if (isTrace) println("* POKE " + dumpName(data) + " <- " + x + " *")
      val ids = inMap(dumpName(data))
      val mask = (BigInt(1) << hostLen) - 1
      for (i <- 0 until ids.size) {
        val shift = hostLen * i
        val value = (x >> shift) & mask 
        pokeMap(ids(ids.size-1-i)) = value
      }
    } else {
      super.poke(data, x)
    }
  }

  override def peek(data: Bits) = {
    if (outMap contains dumpName(data)) {
      var value = BigInt(0)
      val ids = outMap(dumpName(data))
      for (i <- 0 until ids.size) {
        value = value << hostLen | peekMap(ids(ids.size-1-i))
      }
      if (isTrace) println("* PEEK " + dumpName(data) + " <- " + value + " *")
      value
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

  def peekReady = peek(dumpName(c.io.host.out.valid)) == 1

  def peek: BigInt = {
    poke(c.io.host.out.ready, 1)
    while (!peekReady) {
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

  def pokeAll {
    if (isTrace) println("POKE ALL")
    // Send POKE command
    poke(POKE.id)

    // Send Values
    for (i <- 0 until inNum) {
      assert(pokeMap contains i)
      poke(pokeMap(i))
    }
    if (isTrace) println("==========")
  }

  def peekAll {
    if (isTrace) println("PEEK ALL")
    peekMap.clear
    // Send PEEK command
    poke(PEEK.id)
    // Get values
    for (i <- 0 until outNum) {
      peekMap(i) = peek
    }
    if (isTrace) println("==========")
  }

  def peekTrace {
    if (isTrace) println("PEEK TRACE")
    poke(TRACE.id)
    traceMem
  }

  // Memory trace
  private val memreads = LinkedHashMap[Int, BigInt]()
  def traceMem {
    val rcount = peek.toInt
    for (i <- 0 until rcount) {
      var addr = BigInt(0)
      for (k <- 0 until addrLen by hostLen) {
        addr = (addr << hostLen) | peek
      }
      val tag = peek.toInt
      memreads(tag) = addr
    }
  }

  def pokeSteps(n: Int, readNext: Boolean = true) {
    if (isTrace) println("POKE STEPS")
    // Send STEP command
    poke((n << (cmdLen+1)) | ((if (readNext) 1 else 0) << cmdLen) | STEP.id)
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
    if (isTrace) println("SNAPSHOT!")
    val snap = new StringBuilder
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      snap append intToBin(peek, hostLen) 
    }
    if (isTrace) {
      println("SNAPCHAIN: " + snap.result)
      println("===========")
    }
    assert(snap.result.size == snapLen)
    snap.result
  }

  def verifySnap(pokes: List[SampleInst]) {
    if (isTrace) println("VERIFY SNAPSHOT")
    for (poke <- pokes) {
      poke match {
        case Poke(signal, value, off) => {
          val path = targetPath + (signal stripPrefix targetPrefix)
          val peekValue = if (off == -1) peek(path) else peek(path, off)
          expect(value == peekValue, "EXPECT %s <- %d == %d".format(
            if (off == -1) signal else signal + "[%d]".format(off), 
            peekValue, value))
        }
        case _ => // cannnot happen!
      }
    }
    if (isTrace) println("===============")
  }

  // Emulate AXI Slave
  private val dram = HashMap[BigInt, BigInt]()
  private val memrw = ScalaQueue[Boolean]()
  private val memtag = ScalaQueue[BigInt]()
  private val memaddr = ScalaQueue[BigInt]()
  private var memcycles = -1
  private val readcycles = 2
  private val writecycles = 1
  def tickMem {
    def read(addr: BigInt) = {
      var data = BigInt(0)
      for (i <- 0 until (memLen-1)/8+1) {
        data |= dram(addr+i) << (8*i)
      }
      data
    }
    def write(addr: BigInt, data: BigInt) {
      for (i <- 0 until (memLen-1)/8+1) {
        dram(addr+i) = (data >> (8*i)) & 0xff
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
        memrw enqueue (peek(c.io.mem.req_cmd.bits.rw) == 1)
        memtag enqueue peek(c.io.mem.req_cmd.bits.tag)
        memaddr enqueue peek(c.io.mem.req_cmd.bits.addr)
        if (!memrw.head) poke(c.io.mem.req_cmd.ready, 1)
      }
      if (peek(dumpName(c.io.mem.req_data.valid)) == 1) {
        val data = peek(c.io.mem.req_data.bits.data)
        poke(c.io.mem.req_cmd.ready, 1)
        poke(c.io.mem.req_data.ready, 1)
        write(memaddr.head, data)
      }
      assert(memrw.size == memtag.size)
      assert(memrw.size == memaddr.size)
      if (!memrw.isEmpty) {
        memcycles = if (!memrw.head) readcycles else writecycles
      }
    } else {
      val addr = memaddr.dequeue
      val tag = memtag.dequeue
      if (!memrw.dequeue) {
        val data = read(addr)
        poke(c.io.mem.resp.bits.data, data)
        poke(c.io.mem.resp.bits.tag, tag)
        poke(c.io.mem.resp.valid, 1)
      }
      assert(memrw.size == memtag.size)
      assert(memrw.size == memaddr.size)
      memcycles -= 1
    }
    takeSteps(1)
    poke(c.io.mem.req_cmd.ready, 0)
    poke(c.io.mem.req_data.ready, 0)
    poke(c.io.mem.resp.valid, 0) 
    if (isTrace) println("===========")
  }

  def readMem(addr: BigInt) = {
    poke((0 << cmdLen) | MEM.id)
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

  def writeMem(addr: BigInt, data: ArrayBuffer[Int]) {
    poke((1 << cmdLen) | MEM.id)
    val mask = (BigInt(1) << hostLen) - 1
    for (i <- (addrLen-1)/hostLen+1 until 0 by -1) {
      poke(addr >> (hostLen * (i-1)) & mask)
      tickMem
    }
    for (i <- 0 until data.size) {
      poke(data(i))
      tickMem
    }
    do {
      tickMem
    } while (memcycles >= 0)
  }

  def writeMem(addr: BigInt, data: Int) {
    writeMem(addr, ArrayBuffer(data))
  }

  def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def loadMem(filename: String) {
    val blkLen = memLen / 4
    val chunkNum = memLen / 32
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      assert(line.length % blkLen == 0)
      val base = (i * line.length) / 2
      var offset = 0
      for (k <- (line.length - blkLen) to 0 by blkLen) {
        val data = ArrayBuffer[Int]()
        for (j <- 0 until chunkNum) {
          val s = k + 8 * j
          data += ((parseNibble(line(s)) << 28) | (parseNibble(line(s+1)) << 24)) |
            ((parseNibble(line(s+2)) << 20) | (parseNibble(line(s+3)) << 16)) |
            ((parseNibble(line(s+4)) << 12) | (parseNibble(line(s+5)) << 8)) |
            ((parseNibble(line(s+6)) << 4) | parseNibble(line(s+7)))
        }
        writeMem(base+offset, data)
        data.clear
        offset += 4
      }
    }
  }

  def fastLoadMem(filename: String) {
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      var write = BigInt(0)
      for (k <- (line.length - 2) to 0 by -2) {
        val addr = base + offset
        val data = (parseNibble(line(k)) << 4) | parseNibble(line(k+1))
        dram(addr) = data
        offset += 1
      }
    }
  }

  var readNext = false
  var r = 0
  override def step(n: Int) {
    require(n % stepSize == 0)
    if (isTrace) println("STEP " + n + " -> " + t + n)
    if (readNext) recordIo(r, n)
    val index = t / stepSize
    r = if (index < sampleNum || sampleCheck) index else rnd.nextInt(index+1)
    readNext = r < sampleNum || sampleCheck
    pokeAll
    pokeSteps(n, readNext)
    while(!peekReady) { tickStep }
    assert(peek == 0)
    if (readNext) doSampling(r)
    peekAll
    t += n
  }

  def tickStep { tickMem }

  def recordIo(r: Int, n: Int) {
    val sample = samples(r)
    if (sampleCheck) {
      for ((path, ids) <- outMap) {
        val signal = targetPrefix + (path stripPrefix targetPath) 
        val data = (ids foldLeft BigInt(0))((res, i) => (res << hostLen) | (peekMap(i)))
        sample.cmds += Expect(signal, data)
      }
    }
    for ((path, ids) <- inMap) {
      val signal = targetPrefix + (path stripPrefix targetPath) 
      val data = (ids foldLeft BigInt(0))(
        (res, i) => (res << hostLen) | (pokeMap getOrElse (i, BigInt(0))))
      sample.cmds += Poke(signal, data)
    }
    if (sampleCheck) sample.cmds += Step(n)
  }

  def doSampling(r: Int) {
    // record snaps
    val sample = Sample(readSnap)
    verifySnap(sample.cmds.toList)
    // record mems
    peekTrace
    for ((tag, addr) <- memreads)
      sample.cmds += Read(addr, tag)
    memreads.clear

    if (r < samples.size) 
      samples(r) = sample
    else 
      samples += sample 
  }

  def readIoMap(filename: String) {
    object IOType extends Enumeration {
      val IN, OUT = Value
    }
    import IOType._

    val filename = targetPrefix + ".io.map"
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    var iotype = IN
    for (line <- lines) {
      val tokens = line split " "
      tokens.head match {
        case "IN:"  => iotype = IN
        case "OUT:" => iotype = OUT
        case _ => {
          val path = targetPath + (tokens.head stripPrefix targetPrefix)
          val width = tokens.last.toInt
          val n = (width - 1) / hostLen + 1
          iotype match {
            case IN => {
              inMap(path) = ArrayBuffer[BigInt]()
              for (i <- 0 until n) {
                inMap(path) += BigInt(inNum)
                inNum += 1
              }
            }
            case OUT => {
              outMap(path) = ArrayBuffer[BigInt]()
              for (i <- 0 until n) {
                outMap(path) += BigInt(outNum)
                outNum += 1
              }
            }
          }
        }
      }
    }
    for (i <- 0 until inNum) {
      pokeMap(i) = 0
    }
  }

  def readChainMap(filename: String) {
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      val path = tokens.head
      val width = tokens.last.toInt
      Sample.addMapping(path, width)
      snapLen += width
    }
  }
 
  override def finish = {
    val filename = targetPrefix + prefix + ".sample"
    val file = createOutputFile(filename)
    try {
      file write Sample.dump(samples.toList)
    } finally {
      file.close
    }
    super.finish
  }

  readIoMap(targetPrefix + ".io.map")
  readChainMap(targetPrefix + ".chain.map")
}
