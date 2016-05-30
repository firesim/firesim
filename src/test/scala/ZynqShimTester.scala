package strober

import Chisel._
import Chisel.iotesters.{AdvTester, Processable}
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
    verbose: Boolean = true, loadmemType: LoadMemType = FastLoadMem)
    extends StroberTester(c, verbose) {
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

  implicit def bigIntToInt(b: BigInt) = b.toInt

  private val MAXI_aw = new ChannelSource(c.io.master.aw,
    (aw: NastiWriteAddressChannel, in: NastiWriteAddr) => {
      _poke(aw.id, in.addr) ; _poke(aw.addr, in.addr) })
  private val MAXI_w = new ChannelSource(c.io.master.w,
    (w: NastiWriteDataChannel, in: NastiWriteData) => _poke(w.data, in.data))
  private val MAXI_b = new ChannelSink(c.io.master.b,
    (b: NastiWriteResponseChannel) => new NastiWriteResp(_peek(b.id), _peek(b.resp)))
  private val MAXI_ar = new ChannelSource(c.io.master.ar,
    (ar: NastiReadAddressChannel, in: NastiReadAddr) => {
      _poke(ar.id, in.addr) ; _poke(ar.addr, in.addr) })
  private val MAXI_r = new ChannelSink(c.io.master.r,
    (r: NastiReadDataChannel) => new NastiReadData(_peek(r.id), _peek(r.data), _peek(r.last)))
 
  private val mem = Array.fill(1<<24){0.toByte} // size = 16MB
  private val addrOffset = log2Up(c.master.nastiXAddrBits/8)

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

  /*
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

  case class MemWriteInfo(id: Int, addr: Int, len: Int, size: Int, k: Int)
  private var wr_info: Option[MemWriteInfo] = None
  private def tickMem {
    addEvent(new MuteEvent())
    wr_info match {
      case Some(MemWriteInfo(id, aw, len, size, k)) if _peek(c.io.slave.w.valid) =>
        // handle write data
        val data = _peek(c.io.slave.w.bits.data)
        addEvent(new NastiWriteEvent(aw+k*size, data))
        (0 until size) foreach (i => mem(aw+k*size+i) = ((data >> (8*i)) & 0xff).toByte)
        _poke(c.io.slave.w.ready, 1)
        val last = _peek(c.io.slave.w.bits.last)
        takeStep
        assert(k < len || last && k == len)
        _poke(c.io.slave.w.ready, 0)
        _poke(c.io.slave.b.bits.id, id)
        _poke(c.io.slave.b.bits.resp, 0)
        _poke(c.io.slave.b.bits.user, 0)
        if (last) {
          _poke(c.io.slave.b.valid, 1)
          takeStep
          _poke(c.io.slave.b.valid, 0)
          wr_info = None
        } else {
          _poke(c.io.slave.b.valid, 0)
          wr_info = Some(new MemWriteInfo(id, aw, len, size, k+1))
        }
      case None if _peek(c.io.slave.aw.valid) =>
        // handle write address
        wr_info = Some(new MemWriteInfo(
          _peek(c.io.slave.aw.bits.id).toInt,
          _peek(c.io.slave.aw.bits.addr).toInt & 0xffffff,
          _peek(c.io.slave.aw.bits.len).toInt,
          1 << _peek(c.io.slave.aw.bits.size).toInt, 0))
        _poke(c.io.slave.aw.ready, 1)
        takeStep
        _poke(c.io.slave.aw.ready, 0)
      case None if _peek(c.io.slave.ar.valid) =>
        // handle read address
        val ar = _peek(c.io.slave.ar.bits.addr).toInt & 0xffffff
        val tag = _peek(c.io.slave.ar.bits.id)
        val len = _peek(c.io.slave.ar.bits.len).toInt
        val size = 1 << _peek(c.io.slave.ar.bits.size).toInt
        _poke(c.io.slave.ar.ready, 1)
        do { takeStep } while (!_peek(c.io.slave.r.ready))
        _poke(c.io.slave.ar.ready, 0)
        // handle read data
        for (k <- 0 to len) {
          val data = ((0 until size) foldLeft BigInt(0))((res, i) => 
            res | BigInt(mem(ar+k*size+i) & 0xff) << (8*i))
          addEvent(new NastiReadEvent(ar+k*size, data))
          _poke(c.io.slave.r.bits.data, data)
          _poke(c.io.slave.r.bits.id, tag)
          _poke(c.io.slave.r.bits.last, if (k == len) 1 else 0)
          _poke(c.io.slave.r.valid, 1)
          do { takeStep } while (!_peek(c.io.slave.r.ready))
          _poke(c.io.slave.r.bits.last, 0)
          _poke(c.io.slave.r.valid, 0)
        }
      case _ =>
        _poke(c.io.slave.ar.ready, 0)
        _poke(c.io.slave.r.bits.last, 0)
        _poke(c.io.slave.r.valid, 0)
        _poke(c.io.slave.aw.ready, 0)
        _poke(c.io.slave.w.ready, 0)
    }
    addEvent(new UnmuteEvent())
  }
  
  private def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def loadMem(filename: String) = loadmemType match {
    case FastLoadMem => fastLoadMem(filename)
    case SlowLoadMem => slowLoadMem(filename)
  }

  def fastLoadMem(filename: String) {
    scala.io.Source.fromFile(filename).getLines.zipWithIndex foreach {case (line, i) =>
      val base = (i * line.length) / 2
      (((line.length - 2) to 0 by -2) foldLeft 0){ (offset, k) =>
        mem(base+offset) = ((parseNibble(line(k)) << 4) | parseNibble(line(k+1))).toByte
        offset + 1
      }
    }
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
