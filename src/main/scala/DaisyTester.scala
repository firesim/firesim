package daisy

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap}
import scala.io.Source

// Enum Types to read IO maps
object IOTYPE extends Enumeration {
  type IOTYPE = Value
  val DIN, DOUT, WIN, WOUT = Value
}

abstract class DaisyTester[+T <: DaisyShim[Module]](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val signalMap = HashMap[String, Node]() 
  val dInMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val dOutMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val wInMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val wOutMap = LinkedHashMap[String, ArrayBuffer[BigInt]]()
  val pokeMap = HashMap[BigInt, BigInt]()
  val peekMap = HashMap[BigInt, BigInt]()
  val pokedMap = HashMap[BigInt, BigInt]()
  val peekdMap = HashMap[BigInt, BigInt]()
  val signals = ArrayBuffer[String]()
  val widths = ArrayBuffer[Int]()
  val snaps = new StringBuilder
  var snapfilename = ""

  lazy val targetPath = c.target.getPathName(".")
  lazy val targetPrefix = Driver.backend.extractClassName(c.target)
  lazy val basedir = ensureDir(Driver.targetDir)

  lazy val hostLen = c.hostLen
  lazy val cmdLen = c.cmdLen
  lazy val addrLen = c.addrLen
  lazy val memLen = c.memLen
  lazy val blkLen = memLen / 8
  val c_STEP :: c_POKE :: c_PEEK :: c_POKED :: c_PEEKED :: c_TRACE :: c_MEM :: _ = (0 until math.pow(2, cmdLen).toInt).toList
  val step_FIN :: step_TRACE :: step_PEEKD :: _ = (0 until 3).toList

  var dInNum = 0
  var dOutNum = 0
  var wInNum = 0
  var wOutNum = 0

  override def poke(data: Bits, x: BigInt) {
    if (wInMap contains dumpName(data)) {
      if (isTrace) println("* POKE " + dumpName(data) + " <- " + x + " *")
      val ids = wInMap(dumpName(data))
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
    if (wOutMap contains dumpName(data)) {
      var x = BigInt(0)
      val ids = wOutMap(dumpName(data))
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
    if (isTrace) println("Poke All")
    // Send POKE command
    stayIdle()
    poke(c_POKE)

    // Send Values
    for (i <- 0 until wInNum) {
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
    poke(c_PEEK)
    // Get values
    for (i <- 0 until wOutNum) {
      stayIdle()
      peekMap(i) = peek
    }
    stayIdle()
    if (isTrace) println("==========")
  }

  def peekTrace {
    if (isTrace) println("Peek Trace")
    stayIdle()
    poke(c_TRACE)
    traceMem
  }

  def pokeSteps(n: Int, isRecord: Boolean = true) {
    if (isTrace) println("Poke Steps")
    // Send STEP command
    poke((n << (cmdLen+1)) | ((if (isRecord) 1 else 0) << cmdLen) | c_STEP)
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

  def recordIOs {
    // record inputs
    for ((path, ids) <- wInMap) {
      val signal = targetPrefix + (path stripPrefix targetPath) 
      val data = (ids foldLeft BigInt(0))(
        (res, i) => (res << hostLen) | (pokeMap getOrElse (i, BigInt(0))))
      snaps append "%s %x\n".format(signal, data)
    }
    // record outputs
    for ((path, ids) <- wOutMap) {
      val signal = targetPrefix + (path stripPrefix targetPath) 
      val data = (ids foldLeft BigInt(0))((res, i) => (res << hostLen) | (peekMap(i)))
      snaps append "%s %x\n".format(signal, data)
    }
    snaps append "//\n" // indicate the end of one snapshot
  }

  def recordSnap(snap: String) {
    // write registers & srams
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
        snaps append "%s %x\n".format(signal, value)
      } 
      start += width
    }
  }
  
  def recordMem { 
    // Mem Writes
    for ((addr, data) <- memwrites) {
      snaps append "Mem[%x] %08x\n".format(addr, data) 
    } 
    for (addr <- memreads) {
      snaps append "Mem[%x]\n".format(addr)
    }
    memwrites.clear
    memreads.clear
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
    poke((0 << cmdLen) | c_MEM)
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
    memwrites(addr) = data // Memory trace
    poke((1 << cmdLen) | c_MEM)
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
        val data = BigInt((parseNibble(line(k)) << 4) | parseNibble(line(k+1)))
        dram(base+offset) = data
        write = write | (data << (8*((base+offset)%blkLen)))
        if ((base+offset) % blkLen == blkLen-1) {
          memwrites((base+offset)-(blkLen-1)) = write
          write = BigInt(0)
        }
        offset += 1
      }
    }
  }

  // Memory trace
  private val memwrites = LinkedHashMap[BigInt, BigInt]()
  private val memreads = ArrayBuffer[BigInt]()
  def traceMem {
    val waddr = ArrayBuffer[BigInt]()
    val wdata = ArrayBuffer[BigInt]()
    val wcount = peek.toInt
    for (i <- 0 until wcount) {
      var addr = BigInt(0)
      for (k <- 0 until addrLen by hostLen) {
        addr = (addr << hostLen) | peek
      }
      waddr += addr
    }
    for (i <- 0 until wcount) {
      var data = BigInt(0)
      for (k <- 0 until memLen by hostLen) {
        data = (data << hostLen) | peek
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
      memreads += addr
    }
  }

  override def step(n: Int = 1) {
    if (t > 0) recordIOs

    val target = t + n
    if (isTrace) println("STEP " + n + " -> " + target)
    pokeAll
    pokeSteps(n)
    var fin = false
    while (!fin) {
      tickMem
      if (peekReady) {
        val ret = peek
        if (ret == step_FIN) fin = true
        else if (ret == step_TRACE) traceMem
        else if (ret == step_PEEKD) { /* TODO */ } 
      }
    }
    val snap = readSnap 
    peekAll
    peekTrace
    recordSnap(snap)
    recordMem
    t += n
  }

  def readIoMapFile(filename: String) {
    import IOTYPE._
    val filename = targetPrefix + ".io.map"
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    var iotype = DIN
    for (line <- lines) {
      val tokens = line split " "
      tokens.head match {
        case "DIN:"  => iotype = DIN
        case "DOUT:" => iotype = DOUT
        case "WIN:"  => iotype = WIN
        case "WOUT:" => iotype = WOUT
        case _ => {
          val path = targetPath + (tokens.head stripPrefix targetPrefix)
          val width = tokens.last.toInt
          val map = iotype match {
            case DIN => dInMap
            case DOUT => dOutMap
            case WIN => wInMap
            case WOUT => wOutMap
          }
          val n = (width - 1) / hostLen + 1
          map(path) = ArrayBuffer[BigInt]()
          for (i <- 0 until n) {
            val num = iotype match {
              case DIN => dInNum
              case DOUT => dOutNum
              case WIN => wInNum
              case WOUT => wOutNum
            }
            map(path) += BigInt(num)
            iotype match {
              case DIN => dInNum += 1
              case DOUT => dOutNum += 1
              case WIN => wInNum += 1
              case WOUT => wOutNum += 1
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
      snapfile write snaps.result
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
  snapfilename = targetPrefix + ".snap"
}
