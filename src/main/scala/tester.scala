package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, Queue => ScalaQueue}
import scala.io.Source

abstract class StroberTester[+T <: Strober[Module]](c: T, isTrace: Boolean = true, sampleCheck : Boolean = true) extends Tester(c, isTrace) {
  val qInMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val qOutMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val wInMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val wOutMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val pokeMap = HashMap[BigInt, BigInt]()
  val peekMap = HashMap[BigInt, BigInt]()
  val pokeqMap = HashMap[BigInt, ScalaQueue[BigInt]]()
  val peekqMap = HashMap[BigInt, ScalaQueue[BigInt]]()

  val targetPath = c.target.getPathName(".")
  val targetPrefix = Driver.backend.extractClassName(c.target)
  val basedir = ensureDir(Driver.targetDir)

  val hostLen = c.hostLen
  val addrLen = c.addrLen
  val tagLen = c.tagLen
  val memLen = c.memLen
  val cmdLen = c.cmdLen
  val traceLen  = c.traceLen
  val blkLen = memLen / 8

  var qInNum = 0
  var qOutNum = 0
  var wInNum = 0
  var wOutNum = 0

  val samples = ArrayBuffer[Sample]()
  var sampleNum = 20
  var stepSize = 1
  var prefix = ""

  import Cmd._

  override def poke(data: Bits, x: BigInt) {
    if (wInMap contains dumpName(data)) {
      if (isTrace) println("* POKE " + dumpName(data) + " <- " + x + " *")
      val ids = wInMap(dumpName(data))
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
    if (wOutMap contains dumpName(data)) {
      var value = BigInt(0)
      val ids = wOutMap(dumpName(data))
      for (i <- 0 until ids.size) {
        value = value << hostLen | peekMap(ids(ids.size-1-i))
      }
      if (isTrace) println("* PEEK " + dumpName(data) + " <- " + value + " *")
      value
    } else {
      super.peek(data)
    }
  }

  def pokeq(data: Bits, x: BigInt) {
    assert(qInMap contains dumpName(data))
    val ids = qInMap(dumpName(data))
    val mask = (BigInt(1) << hostLen) - 1
    for (i <- 0 until ids.size) {
      val shift = hostLen * i
      val value = (x >> shift) & mask
      assert(pokeqMap contains ids(ids.size-1-i))
      pokeqMap(ids(ids.size-1-i)) enqueue value
    }
  }

  def peekq(data: Bits) = {
    assert(qOutMap contains dumpName(data))
    var value = BigInt(0)
    val ids = qOutMap(dumpName(data))
    for (i <- 0 until ids.size) {
      assert(peekqMap contains ids(ids.size-1-i))
      value = value << hostLen | peekqMap(ids(ids.size-1-i)).dequeue
    }
    value
  }

  def peekqValid(data: Bits) = {
    assert(qOutMap contains dumpName(data))
    var valid = true
    val ids = qOutMap(dumpName(data))
    for (i <- 0 until ids.size) {
      assert(peekqMap contains ids(ids.size-1-i))
      valid &= !peekqMap(ids(ids.size-1-i)).isEmpty
    }
    valid
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

  def stayIdle(n: Int = 2) {
    // the mathine is idle in realistic cases
    takeSteps(n) 
  }

  def pokeAll {
    if (isTrace) println("POKE ALL")
    // Send POKE command
    stayIdle()
    poke(POKE.id)

    // Send Values
    for (i <- 0 until wInNum) {
      stayIdle()
      assert(pokeMap contains i)
      poke(pokeMap(i))
    }
    stayIdle()
    if (isTrace) println("==========")
  }

  def peekAll {
    if (isTrace) println("PEEK ALL")
    stayIdle()
    peekMap.clear
    // Send PEEK command
    poke(PEEK.id)
    // Get values
    for (i <- 0 until wOutNum) {
      stayIdle()
      peekMap(i) = peek
    }
    stayIdle()
    if (isTrace) println("==========")
  }

  def pokeqAll {
    if (isTrace) println("POKEQ ALL")
    stayIdle()
    // Send POKEQ command
    if (qInNum > 0) poke(POKEQ.id)
   
    for (i <- 0 until qInNum) {
      val count = math.min(pokeqMap(i).size, traceLen)
      poke(count)
      for (k <- 0 until count) {
        poke(pokeqMap(i).dequeue)
      } 
    }
    if (isTrace) println("==========")
  }

  def peekqAll {
    if (isTrace) println("PEEKQ ALL")
    stayIdle()
    // Send PEEKQ command
    if (qOutNum > 0) poke(PEEKQ.id)
    traceQout
    if (isTrace) println("==========")
  }

  def traceQout = {
    for (i <- 0 until qOutNum) {
      val count = peek.toInt
      for (k <- 0 until count) {
        peekqMap(i) enqueue peek
      } 
    }
  }

  def peekTrace {
    if (isTrace) println("PEEK TRACE")
    stayIdle()
    poke(TRACE.id)
    traceMem
  }

  // Memory trace
  private val memwrites = LinkedHashMap[BigInt, BigInt]()
  private val memreads = LinkedHashMap[BigInt, BigInt]()
  def traceMem {
    val waddr = ArrayBuffer[BigInt]()
    val wdata = ArrayBuffer[BigInt]()
    val wcount = peek.toInt
    for (i <- 0 until wcount) {
      var addr = BigInt(0)
      for (k <- 0 until addrLen by hostLen) {
        addr |= peek << k
      }
      waddr += addr
    }
    for (i <- 0 until wcount) {
      var data = BigInt(0)
      for (k <- 0 until memLen by hostLen) {
        data |= peek << k
      }
      wdata += data
    }
    for ((addr, data) <- waddr zip wdata) {
      memwrites(addr) = data
    }
    waddr.clear
    wdata.clear
    val rcount = peek.toInt
    for (i <- 0 until rcount) {
      var addr = BigInt(0)
      for (k <- 0 until addrLen by hostLen) {
        addr = (addr << hostLen) | peek
      }
      var tag = BigInt(0)
      for (k <- 0 until tagLen by hostLen) {
        tag = (tag << hostLen) | peek
      }
      memreads(tag) = addr
    }
  }

  def pokeSteps(n: Int, isRecord: Boolean = true) {
    if (isTrace) println("POKE STEPS")
    // Send STEP command
    poke((n << (cmdLen+1)) | ((if (isRecord) 1 else 0) << cmdLen) | STEP.id)
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
    var offset = 0
    while (peek(dumpName(c.io.host.in.ready)) == 0) {
      snap append intToBin(peek, hostLen) 
    }
    if (isTrace) {
      println("Chain: " + snap.result)
      println("===========")
    }
    snap.result
  }

  def verifySnap(pokes: List[SampleInst]) {
    if (isTrace) println("VERIFY SNAPSHOT")
    val MemRegex = """([\w\.]+)\[(\d+)\]""".r
    for (poke <- pokes) {
      poke match {
        case Poke(signal, value, off) => {
          val path = targetPath + (signal stripPrefix targetPrefix)
          val peekValue = if (off == -1) peek(path) else peek(path, off)
          expect(value == peekValue, "EXPECT %s <- %d == %d".format(signal, peekValue, value))
        }
        case _ => // cannnot happen!
      }
    }
    if (isTrace) println("===============")
  }

  // Emulate AXI Slave
  private val dram = HashMap[BigInt, BigInt]()
  private var memrw   = false
  private var memtag  = BigInt(0)
  private var memaddr = BigInt(0)
  private var memdata = BigInt(0)
  private var memcycles = -1
  def tickMem {
    def read(addr: BigInt) = {
      var data = BigInt(0)
      for (i <- 0 until (memLen-1)/8+1) {
        data |= dram(memaddr+i) << (8*i)
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

  def writeMem(addr: BigInt, data: BigInt) {
    // memwrites(addr) = data // Memory trace, deprecated
    poke((1 << cmdLen) | MEM.id)
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
      var write = BigInt(0)
      for (k <- (line.length - 2) to 0 by -2) {
        val addr = base + offset
        val data = BigInt((parseNibble(line(k)) << 4) | parseNibble(line(k+1)))
        dram(addr) = data
        offset += 1
      }
    }
  }

  var sampleRec = false
  var sampleIdx = -1
  override def step(n: Int) {
    require(n % stepSize == 0)
    if (sampleRec) {
      recordIo(sampleIdx, n)
    }
    pokeAll
    pokeqAll
    val rIdx = t / stepSize
    val sIdx = if (rIdx < sampleNum) rIdx else rnd.nextInt(rIdx+1)
    sampleRec = sampleCheck || sIdx < sampleNum 
    if (isTrace) println("STEP " + n + " -> " + t + n)
    pokeSteps(n, sampleRec)
    var fin = false
    while (!fin) {
      tickMem
      if (peekReady) {
        val resp = peek
        if (resp == StepResp.FIN.id) fin = true
        else if (resp == StepResp.TRACE.id) traceMem
        else if (resp == StepResp.PEEKQ.id) traceQout
      }
    }
    if (sampleRec) {
      doSampling(rIdx, sIdx, sampleIdx)
      sampleIdx = sIdx
    }
    peekAll
    peekqAll
    t += n
  }

  def recordIo(idx: Int, n: Int) {
    val sample = if (sampleCheck) samples.last else samples(idx)
    // Record inputs
    for ((path, ids) <- wInMap) {
      val signal = targetPrefix + (path stripPrefix targetPath) 
      val data = (ids foldLeft BigInt(0))(
        (res, i) => (res << hostLen) | (pokeMap getOrElse (i, BigInt(0))))
      sample.cmds += Poke(signal, data)
    }
    // Record outputs
    if (sampleCheck) {
      for ((path, ids) <- wOutMap) {
        val signal = targetPrefix + (path stripPrefix targetPath) 
        val data = (ids foldLeft BigInt(0))((res, i) => (res << hostLen) | (peekMap(i)))
        sample.cmds += Expect(signal, data)
      }
      sample.cmds += Step(n)
    }
  }

  def doSampling(rIdx: Int, thisIdx: Int, prevIdx: Int) {
    // record snaps
    val sample = Sample(rIdx, readSnap)
    verifySnap(sample.cmds.toList)
    // record mems
    peekTrace
    for ((tag, addr) <- memreads)
      sample.cmds += Read(addr, tag)
    for ((addr, data) <- memwrites)
      sample.mem(addr) = data
    memreads.clear
    memwrites.clear
    if (sampleCheck || samples.size < sampleNum) { 
      if (!samples.isEmpty) {
        val last = samples.last
        last.next = Some(sample)
        sample.prev = Some(last)
      }
      samples += sample 
    } else {
      // mergy memory
      samples(thisIdx).next match {
        case None => {
          assert(thisIdx == prevIdx)
          sample.mem ++= samples(thisIdx).mem
          samples(thisIdx).prev match {
            case None =>
            case Some(prev) => {
              sample.prev = Some(prev)
              prev.next = Some(sample)
            }
          }
        }
        case Some(next) => {
          next.mem ++= samples(thisIdx).mem
          samples(thisIdx).prev match {
            case None =>
            case Some(prev) => {
              prev.next = Some(next)
              next.prev = Some(prev)
            }
          }
        }
        val last = samples(prevIdx)
        sample.prev = Some(last)
        last.next = Some(sample)
        samples(thisIdx) = sample
      }
    }
  }

  def readIoMap(filename: String) {
    object IOType extends Enumeration {
      val QIN, QOUT, WIN, WOUT = Value
    }
    import IOType._

    val filename = targetPrefix + ".io.map"
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    var iotype = QIN
    for (line <- lines) {
      val tokens = line split " "
      tokens.head match {
        case "QIN:"  => iotype = QIN
        case "QOUT:" => iotype = QOUT
        case "WIN:"  => iotype = WIN
        case "WOUT:" => iotype = WOUT
        case _ => {
          val path = targetPath + (tokens.head stripPrefix targetPrefix)
          val width = tokens.last.toInt
          val n = (width - 1) / hostLen + 1
          iotype match {
            case QIN => {
              qInMap(path) = ArrayBuffer[BigInt]()
              for (i <- 0 until n) {
                qInMap(path) += BigInt(qInNum)
                qInNum += 1
              }
            }
            case QOUT => {
              qOutMap(path) = ArrayBuffer[BigInt]()
              for (i <- 0 until n) {
                qOutMap(path) += BigInt(qOutNum)
                qOutNum += 1
              }
            }
            case WIN => {
              wInMap(path) = ArrayBuffer[BigInt]()
              for (i <- 0 until n) {
                wInMap(path) += BigInt(wInNum)
                wInNum += 1
              }
            }
            case WOUT => {
              wOutMap(path) = ArrayBuffer[BigInt]()
              for (i <- 0 until n) {
                wOutMap(path) += BigInt(wOutNum)
                wOutNum += 1
              }
            }
          }
        }
      }
    }
    // initialize pokeqMap & peekqMap
    for (i <- 0 until wInNum) {
      pokeMap(i) = 0
    }
    for (i <- 0 until qInNum) {
      pokeqMap(i) = ScalaQueue[BigInt]()
    }
    for (i <- 0 until qOutNum) {
      peekqMap(i) = ScalaQueue[BigInt]()
    }
  }

  def readChainMap(filename: String) {
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      Sample.addMapping(tokens.head, tokens.last.toInt)
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
