package strober
package testers

import junctions._
import scala.collection.mutable.{HashMap, ArrayBuffer, Queue => ScalaQueue}
import java.io.{File, InputStream}

private case class NastiReadAddr(id: Int, addr: Int, size: Int = 0, len: Int = 0)
private case class NastiReadData(id: Int, data: BigInt, last: Boolean = true)
private case class NastiWriteAddr(id: Int, addr: Int, size: Int = 0, len: Int = 0)
private case class NastiWriteData(data: BigInt, last: Boolean = true)
private case class NastiWriteResp(id: Int, resp: Int = 0)
 
trait LoadMemType
case object FastLoadMem extends LoadMemType
case object SlowLoadMem extends LoadMemType

abstract class ZynqShimTester[+T <: SimNetwork](
    c: ZynqShim[T],
    verbose: Boolean = true,
    sampleFile: Option[File] = None,
    logFile: Option[File] = None,
    loadmemType: LoadMemType = SlowLoadMem) extends StroberTester(c, verbose, sampleFile, logFile) {
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
 
  private val addrOffset = chisel3.util.log2Up(c.master.nastiXAddrBits/8)

  protected[testers] def pokeChannel(addr: Int, data: BigInt) {
    MAXI_aw.inputs enqueue (new NastiWriteAddr(0, addr << addrOffset))
    MAXI_w.inputs enqueue (new NastiWriteData(data))
    _eventually(!MAXI_b.outputs.isEmpty)
    MAXI_b.outputs.clear
  }

  protected[testers] def peekChannel(addr: Int) = {
    MAXI_ar.inputs enqueue (new NastiReadAddr(0, addr << addrOffset))
    _eventually(!MAXI_r.outputs.isEmpty)
    MAXI_r.outputs.dequeue.data
  }

  override def setTraceLen(len: Int) { 
    super.setTraceLen(len)
    pokeChannel(ZynqCtrlSignals.TRACELEN.id, len)
  }

  def writeCR(wName: String, crName: String, value: BigInt){
    val addr = c.getCRAddr(wName, crName)
    pokeChannel(addr, value)
  }

  def readCR(wName: String, crName: String) = {
    val addr = c.getCRAddr(wName, crName)
    peekChannel(addr)
  }

  override def reset(n: Int) {
    for (_ <- 0 until n) {
      pokeChannel(ZynqCtrlSignals.HOST_RESET.id, 0)
      pokeChannel(ZynqCtrlSignals.SIM_RESET.id, 0)
      _eventually(peekChannel(ZynqCtrlSignals.DONE.id))
      _peekMap.clear
      // flush junk output tokens
      c.OUT_ADDRS foreach {case (out, addr) =>
        _peekMap(out) = peekChunks(addr, SimUtils.getChunks(out))
      }
      // flush junk traces
      super.reset(1)
    }
  }

  override def _tick(n: Int) {
    pokeChannel(ZynqCtrlSignals.STEP.id, n)
    c.IN_ADDRS foreach {case (in, addr) =>
      pokeChunks(addr, SimUtils.getChunks(in), _pokeMap getOrElse (in, BigInt(rnd.nextInt)))
    }
    _eventually(peekChannel(ZynqCtrlSignals.DONE.id))
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
      depth: Int = 1 << 20) extends chisel3.iotesters.Processable {
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
    private def loadMem(lines: Iterator[String]) {
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
    def loadMem(file: File) {
      loadMem(io.Source.fromFile(file).getLines)
    }
    def loadMem(stream: InputStream) {
      loadMem(io.Source.fromInputStream(stream).getLines)
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

  def loadMem(file: File) = loadmemType match {
    case FastLoadMem => mem loadMem file
    case SlowLoadMem => slowLoadMem(file)
  }

  def loadMem(stream: InputStream) = loadmemType match {
    case FastLoadMem => mem loadMem stream
    case SlowLoadMem => slowLoadMem(stream)
  }

  def writeMem(addr: BigInt, data: BigInt) {
    pokeChunks(c.AW_ADDR, SimUtils.getChunks(c.io.slave.aw.bits.addr), addr)
    pokeChunks(c.W_ADDR,  SimUtils.getChunks(c.io.slave.w.bits.data),  data)
  }

  def readMem(addr: BigInt) = {
    pokeChunks(c.AR_ADDR, SimUtils.getChunks(c.io.slave.ar.bits.addr), addr)
    peekChunks(c.R_ADDR,  SimUtils.getChunks(c.io.slave.r.bits.data))
  }

  private def slowLoadMem(lines: Iterator[String]) {
    val chunk = c.arb.nastiXDataBits / 4
    lines.zipWithIndex foreach {case (line, i) =>
      val base = (i * line.length) / 2
      assert(line.length % chunk == 0)
      (((line.length - chunk) to 0 by -chunk) foldLeft 0){ (offset, j) =>
        writeMem(base+offset, ((0 until chunk) foldLeft BigInt(0)){ (res, k) =>
          res | (BigInt(parseNibble(line(j+k))) << (4*(chunk-1-k)))
        })
        offset + chunk / 2
      }
    }
  }

  def slowLoadMem(file: File) {
    println(s"[LOADMEM] LOADING $file")
    slowLoadMem(scala.io.Source.fromFile(file).getLines)
    println(s"[LOADMEM] DONE")
  }

  def slowLoadMem(stream: InputStream) {
    slowLoadMem(scala.io.Source.fromInputStream(stream).getLines)
  }

  protected[testers] def readChain(t: ChainType.Value) = {
    val chain = new StringBuilder
    for (_ <- 0 until chainLoop(t) ; i <- 0 until c.master.io.daisy(t).size) {
      t match {
        case ChainType.SRAM => pokeChannel(c.SRAM_RESTART_ADDR + i, 0)
        case _ =>
      }
      for (_ <- 0 until chainLen(t)) {
        chain append intToBin(peekChannel(c.DAISY_ADDRS(t) + i), c.sim.daisyWidth)
      }
    }
    chain.result
  }

  reset(5)
}
