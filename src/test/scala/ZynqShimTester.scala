package strober

import chisel3._
import junctions._
import scala.collection.immutable.ListMap
import scala.collection.mutable.{HashMap, ArrayBuffer, Queue => ScalaQueue}

private case class NastiReadAddr(id: Int, addr: Int, size: Int = 0, len: Int = 0)
private case class NastiReadData(id: Int, data: BigInt, last: Boolean = true)
private case class NastiWriteAddr(id: Int, addr: Int, size: Int = 0, len: Int = 0)
private case class NastiWriteData(data: BigInt, last: Boolean = true)
private case class NastiWriteResp(id: Int, resp: Int = 0)
 
trait LoadMemType
case object FastLoadMem extends LoadMemType
case object SlowLoadMem extends LoadMemType

abstract class ZynqShimTester[+T <: SimNetwork](c: ZynqShim[T],
    verbose: Boolean = true, loadmemType: LoadMemType = FastLoadMem,
    logFile: Option[String] = None, waveform: Option[String] = None, testCmd: List[String] = Nil)
    extends StroberTester(c, verbose, logFile=logFile, waveform=waveform, testCmd=testCmd) {
  /* protected[strober] val inMap = c.master.inMap
  protected[strober] val outMap = c.master.outMap
  protected[strober] val inTrMap = c.master.inTrMap
  protected[strober] val outTrMap = c.master.outTrMap
  protected[strober] def chunk(wire: Bits) = c.sim.io.chunk(wire)
  protected[strober] val sampleNum = c.sim.sampleNum
  protected[strober] val channelOff = log2Up(c.sim.channelWidth) */
  private val targetName = c.sim match {case w: SimWrapper[_] => w.target.name}
  protected[strober] val _inputs =
    (c.sim.io.inputs map {case (x, y) => x -> s"${targetName}.$y"}).toMap
  protected[strober] val _outputs =
    (c.sim.io.outputs map {case (x, y) => x -> s"${targetName}.$y"}).toMap
  protected[strober] implicit val channelWidth = c.sim.channelWidth

  private implicit def bigIntToInt(b: BigInt) = b.toInt

  private val MAXI_aw = new ChannelSource(c.io.master.aw, (aw: NastiWriteAddressChannel, in: NastiWriteAddr) =>
    { _poke(aw.id, in.id) ; _poke(aw.addr, in.addr) })
  private val MAXI_w = new ChannelSource(c.io.master.w, (w: NastiWriteDataChannel, in: NastiWriteData) =>
    { _poke(w.data, in.data) })
  private val MAXI_b = new ChannelSink(c.io.master.b, (b: NastiWriteResponseChannel) =>
    new NastiWriteResp(_peek(b.id), _peek(b.resp)))
  private val MAXI_ar = new ChannelSource(c.io.master.ar, (ar: NastiReadAddressChannel, in: NastiReadAddr) =>
    { _poke(ar.id, in.id) ; _poke(ar.addr, in.addr) })
  private val MAXI_r = new ChannelSink(c.io.master.r, (r: NastiReadDataChannel) =>
    new NastiReadData(_peek(r.id), _peek(r.data), _peek(r.last)))
 
  private val addrOffset = util.log2Up(c.master.nastiXAddrBits/8)

  protected[strober] def pokeChannel(addr: Int, data: BigInt) {
    MAXI_aw.inputs enqueue (new NastiWriteAddr(0, addr << addrOffset))
    MAXI_w.inputs enqueue (new NastiWriteData(data))
    _eventually(!MAXI_b.outputs.isEmpty)
    MAXI_b.outputs.clear
  }

  protected[strober] def peekChannel(addr: Int) = {
    MAXI_ar.inputs enqueue (new NastiReadAddr(0, addr << addrOffset))
    _eventually(!MAXI_r.outputs.isEmpty)
    MAXI_r.outputs.dequeue.data
  }

  override def setTraceLen(len: Int) { 
    super.setTraceLen(len)
    pokeChannel(c.TRACELEN_ADDR, len)
  }

  def setMemLatency(cycles: Int) {
    pokeChannel(c.LATENCY_ADDR, cycles)
  }

  override def reset(n: Int) {
    for (_ <- 0 until n) {
      pokeChannel(c.RESET_ADDR, 0)
      // flush output tokens & traces from initialization
      _peekMap.clear
      c.OUT_ADDRS foreach {case (out, addr) =>
        _peekMap(out) = peekChunks(addr, SimUtils.getChunks(out))
      }
    }
  }

  override def _tick(n: Int) {
    pokeChannel(c.STEP_ADDR, n)
    c.IN_ADDRS foreach {case (in, addr) =>
      pokeChunks(addr, SimUtils.getChunks(in), _pokeMap getOrElse (in, BigInt(rnd.nextInt)))
    }
    _eventually(peekChannel(c.DONE_ADDR))
    c.OUT_ADDRS foreach {case (out, addr) =>
      _peekMap(out) = peekChunks(addr, SimUtils.getChunks(out))
    }
  }

  private val SAXI_aw = new ChannelSink(c.io.slave.aw, (aw: NastiWriteAddressChannel) =>
    new NastiWriteAddr(_peek(aw.id), _peek(aw.addr), _peek(aw.size), _peek(aw.len)))
  private val SAXI_w = new ChannelSink(c.io.slave.w, (w: NastiWriteDataChannel) =>
    new NastiWriteData(_peek(w.data), _peek(w.last)))
  private val SAXI_b = new ChannelSource(c.io.slave.b, (b: NastiWriteResponseChannel, in: NastiWriteResp) =>
    { _poke(b.id, in.id) ; _poke(b.resp, in.resp) })
  private val SAXI_ar = new ChannelSink(c.io.slave.ar, (ar: NastiReadAddressChannel) =>
    new NastiReadAddr(_peek(ar.id), _peek(ar.addr), _peek(ar.size), _peek(ar.len)))
  private val SAXI_r = new ChannelSource(c.io.slave.r, (r: NastiReadDataChannel, in: NastiReadData) =>
    { _poke(r.id, in.id) ; _poke(r.data, in.data) ; _poke(r.last, in.last) })
 
  private def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  private class NastiMem(
      arQ: ScalaQueue[NastiReadAddr],  rQ: ScalaQueue[NastiReadData],
      awQ: ScalaQueue[NastiWriteAddr], wQ: ScalaQueue[NastiWriteData],
      bQ: ScalaQueue[NastiWriteResp], latency: Int = 5, word_width: Int = 4,
      depth: Int = 1 << 20, verbose: Boolean = true) extends chisel3.iotesters.Processable {
    import chisel3.util.log2Up
    require(word_width % 4 == 0, "word_width should be divisible by 4")
    private val addrMask = (1 << log2Up(depth))-1
    private val mem = Array.fill(depth){BigInt(0)}
    private def int(b: Byte) = (BigInt((b >>> 1) & 0x7f) << 1) | b & 0x1
    private val off = log2Up(word_width)
    private def read(addr: Int) = {
      if (addr > (1 << 21)) println(s"read addr: $addr")
      val data = mem(addr & addrMask)
      if (verbose) logger println "MEM[%x] => %x".format(addr & addrMask, data)
      data
    }
    private def write(addr: Int, data: BigInt) {
      if (verbose) logger println "MEM[%x] <= %x".format(addr & addrMask, data)
      mem(addr & addrMask) = data
    }
    def loadMem(filename: String) {
      val lines = io.Source.fromFile(filename).getLines
      for ((line, i) <- lines.zipWithIndex) {
        val base = (i * line.length) / 2
        assert(base % word_width == 0)
        ((0 until line.length by 2) foldRight (BigInt(0), 0)){case (k, (data, offset)) =>
          val shift = 8 * (offset % word_width)
          val byte = ((parseNibble(line(k)) << 4) | parseNibble(line(k+1))).toByte
          if ((offset % word_width) == word_width - 1) {
            mem((base+offset)>>off) = data | int(byte) << shift
            (BigInt(0), offset + 1)
          } else {
            (data | int(byte) << shift, offset + 1)
          }
        }
      }
    }

    private var aw: Option[NastiWriteAddr] = None
    private val schedule = Array.fill(latency){ScalaQueue[NastiReadData]()}
    private var cur_cycle = 0
    def process {
      aw match {
        case Some(p) if wQ.size > p.len =>
          assert((1 << p.size) == word_width)
          (0 to p.len) foreach (i =>
            write((p.addr >> off) + i, wQ.dequeue.data))
          bQ enqueue new NastiWriteResp(p.id)
          aw = None
        case None if !awQ.isEmpty => aw = Some(awQ.dequeue)
        case None if !arQ.isEmpty =>
          val ar = arQ.dequeue
          (0 to ar.len) foreach (i =>
            schedule((cur_cycle+latency-1) % latency) enqueue
              new NastiReadData(ar.id, read((ar.addr >> off) + i), i == ar.len))
        case _ =>
      }
      while (!schedule(cur_cycle).isEmpty) {
        rQ enqueue schedule(cur_cycle).dequeue
      }
      cur_cycle = (cur_cycle + 1) % latency
    }

    _preprocessors += this
  }
 
  private val mem = new NastiMem(
    SAXI_ar.outputs, SAXI_r.inputs, SAXI_aw.outputs, SAXI_w.outputs, SAXI_b.inputs,
    word_width=c.arb.nastiXDataBits/8)

  def loadMem(filename: String) = loadmemType match {
    case FastLoadMem => mem loadMem filename
    case SlowLoadMem => // slowLoadMem(filename)
  }

  /*
  def writeMem(addr: BigInt, data: BigInt) {
    addEvent(new MemWriteEvent(addr, data))
    // Address
    pokeChannel(c.master.memMap(c.mem.aw.bits.addr), addr)
    addEvent(new MuteEvent())
    do { takeStep } while (!_peek(c.io.slave.aw.valid))
    addEvent(new UnmuteEvent())
    tickMem
    // Data
    pokeId(c.master.memMap(c.mem.w.bits.data), chunk(c.mem.w.bits.data), data)
    addEvent(new MuteEvent())
    do { takeStep } while (!_peek(c.io.slave.w.valid))
    addEvent(new UnmuteEvent())
    tickMem
  }

  def readMem(addr: BigInt) = {
    // Address
    pokeChannel(c.master.memMap(c.mem.ar.bits.addr), addr)
    addEvent(new MuteEvent())
    do { takeStep } while (!_peek(c.io.slave.ar.valid))
    addEvent(new UnmuteEvent())
    tickMem
    // Data  
    val data = peekId(c.master.memMap(c.mem.r.bits.data), chunk(c.mem.r.bits.data))
    addEvent(new MemReadEvent(addr, data))
    data
  }

  def slowLoadMem(filename: String) {
    addEvent(new DumpEvent(s"[LOADMEM] LOADING ${filename}"))
    val chunk = c.nastiXDataBits / 4
    scala.io.Source.fromFile(filename).getLines.zipWithIndex foreach {case (line, i) =>
      val base = (i * line.length) / 2
      assert(line.length % chunk == 0)
      (((line.length - chunk) to 0 by -chunk) foldLeft 0){ (offset, j) =>
        writeMem(base+offset, ((0 until chunk) foldLeft BigInt(0)){ (res, k) =>
          res | (BigInt(parseNibble(line(j+k))) << (4*(chunk-1-k)))
        })
        offset + chunk / 2
      }
    }
    addEvent(new DumpEvent(s"[LOADMEM] DONE")) 
  }

  protected[strober] def readChain(t: ChainType.Value) = {
    val chain = new StringBuilder
    for (k <- 0 until chainLoop(t)) {
      t match {
        case ChainType.SRAM0 => pokeChannel(c.master.sram0RestartAddr, 0)
        case ChainType.SRAM1 => pokeChannel(c.master.sram1RestartAddr, 0)
        case _ =>
      }
      for (i <- 0 until chainLen(t)) {
        chain append intToBin(peekChannel(c.master.snapOutMap(t)), c.sim.daisyWidth)
      }
    }
    chain.result
  }
  */

  reset(5)
}
