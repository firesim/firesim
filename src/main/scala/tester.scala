package strober

import Chisel._
import Chisel.AdvTester._
import scala.collection.mutable.{HashMap, Queue => ScalaQueue, ArrayBuffer}
import scala.io.Source

abstract class SimTester[+T <: Module](c: T, isTrace: Boolean, snapCheck: Boolean) extends AdvTester(c, false) {
  protected[strober] val pokeMap = HashMap[Int, BigInt]()
  protected[strober] val peekMap = HashMap[Int, BigInt]()
  private var traceCount = 0

  protected[strober] def inMap: Map[Bits, Int]
  protected[strober] def outMap: Map[Bits, Int]
  protected[strober] def inTrMap: Map[Bits, Int]
  protected[strober] def outTrMap: Map[Bits, Int]
  protected[strober] def chunk(wire: Bits): Int

  protected[strober] lazy val chainLoop  = transforms.chainLoop
  protected[strober] lazy val chainLen   = transforms.chainLen

  protected[strober] def sampleNum: Int
  protected[strober] lazy val samples = Array.fill(sampleNum){new Sample}
  protected[strober] var lastSample: Option[(Sample, Int)] = None
  private var _traceLen = 0
  def traceLen = _traceLen

  implicit def bigintToBoolean(b: BigInt) = if (b == 0) false else true

  protected[strober] def channelOff: Int
  protected[strober] def pokeChannel(addr: Int, data: BigInt): Unit
  protected[strober] def peekChannel(addr: Int): BigInt
  protected[strober] def pokeId(id: Int, chunk: Int, data: BigInt) {
    (0 until chunk) foreach (off => pokeChannel(id+off, data >> (off << channelOff)))
  }
  protected[strober] def peekId(id: Int, chunk: Int) = {
    ((0 until chunk) foldLeft BigInt(0))(
      (res, off) => res | (peekChannel(id+off) << (off << channelOff)))
  }
  protected[strober] def _poke(data: Bits, x: BigInt) = super.wire_poke(data, x)
  protected[strober] def _peek(data: Bits) = super.peek(data)

  override def wire_poke(port: Bits, x: BigInt) = this.poke(port, x)

  override def poke(port: Bits, x: BigInt) {
    assert(inMap contains port)
    if (isTrace) println("* POKE %s <- %x *".format(dumpName(port), x))
    pokeMap(inMap(port)) = x
  }
 
  override def peek(port: Bits) = {
    assert(outMap contains port)
    assert(peekMap contains outMap(port))
    val addr = outMap(port)
    val value = peekMap(outMap(port))
    if (isTrace) println("* PEEK %s -> %x *".format(dumpName(port), value))
    value
  }

  override def expect(pass: Boolean, msg: => String) = {
    if (isTrace) println(s"""${msg} : ${if (pass) "PASS" else "FAIL"}""")
    if (!pass) fail
    pass
  }
 
  override def expect(port: Bits, expected: BigInt) = {
    assert(outMap contains port)
    assert(peekMap contains outMap(port))
    val value = peekMap(outMap(port))
    val pass = value == expected 
    expect(pass, "* EXPECT %s -> %x == %x".format(dumpName(port), value, expected))
  }

  protected[strober] def traces(sample: Sample) = {
    for (i <- 0 until traceCount) {
      inTrMap foreach {case (wire, id) => sample addCmd PokePort(wire, peekId(id, chunk(wire)))}
      sample addCmd Step(1)
      outTrMap foreach {case (wire, id) => sample addCmd ExpectPort(wire, peekId(id, chunk(wire)))}
    }
    sample
  }

  protected def intToBin(value: BigInt, size: Int) =
    ((0 until size) map (i => (((value >> (size-1-i)) & 0x1) + '0').toChar) addString new StringBuilder).result

  protected[strober] def readSnapshot: String

  protected[strober] def verifySnapshot(sample: Sample) {
    val pass = (sample map {
      case Load(signal: MemRead,    value, None) => true 
      case Load(signal: MemSeqRead, value, None) => true
      case Load(signal, value, off) => 
        val expected = peekNode(signal, off) 
        expect(expected == value, "%s%s -> %x == %x".format(transforms.nameMap(signal),
          off map (x => s"[${x}]") getOrElse "", expected, value))
      case _ => true   
    }) reduce (_ && _)
    println("* SNAPSHOT: %s".format(if (pass) "PASS" else "FAIL"))
  }

  protected[strober] def _tick(n: Int): Unit

  def setTraceLen(len: Int) {
    _traceLen = len
  }

  override def step(n: Int) {
    if (isTrace) println(s"STEP ${n} -> ${t+n}")
    // reservoir sampling
    if (t % traceLen == 0) {
      val recordId = t / traceLen
      val sampleId = if (recordId < sampleNum) recordId else rnd.nextInt(recordId+1)
      if (sampleId < sampleNum) {
        lastSample match {
          case None =>
          case Some((sample, id)) => samples(id) = traces(sample)
        }
        val sample = Sample(readSnapshot, t)
        lastSample = Some((sample, sampleId)) 
        if (snapCheck) verifySnapshot(sample)
        traceCount = 0 
      }
    }
    // take steps
    _tick(n)
    t += n
    if (traceCount < traceLen) traceCount += n
  }

  protected[strober] def flush {
    // flush output tokens & traces from initialization
    peekMap.clear
    outMap foreach {case (out, id) => peekMap(id) = peekId(id, chunk(out))}
    outTrMap foreach {case (wire, id) => val trace = peekId(id, chunk(wire))}
  }

  override def finish = {
    // tail samples
    lastSample match {
      case None =>
      case Some((sample, id)) => samples(id) = traces(sample)
    }
    val filename = transforms.targetName + ".sample"
    val file = createOutputFile(filename)
    try {
      file write (samples filter (_.cycle >= 0) map (_.toString) mkString "")
    } finally {
      file.close
    }
    super.finish
  }
  if (!snapCheck) ChiselError.info("[Strober Tester] No Snapshot Checking...")
}

abstract class SimWrapperTester[+T <: SimWrapper[Module]](c: T, isTrace: Boolean = true, snapCheck: Boolean = true) extends SimTester(c, isTrace, snapCheck) {
  protected[strober] val inMap = c.io.inMap 
  protected[strober] val outMap = c.io.outMap
  protected[strober] val inTrMap = c.io.inTrMap
  protected[strober] val outTrMap = c.io.outTrMap
  protected[strober] def chunk(wire: Bits) = c.io.chunk(wire)
  protected[strober] val sampleNum = c.sampleNum
  protected[strober] val channelOff = log2Up(c.channelWidth)

  private val ins = c.io.ins
  private val outs = c.io.outs ++ c.io.inT ++ c.io.outT

  protected[strober] def pokeChannel(addr: Int, data: BigInt) {
    while(!_peek(ins(addr).ready)) takeStep
    _poke(ins(addr).bits, data)
    _poke(ins(addr).valid, 1)
    takeStep
    _poke(ins(addr).valid, 0)
  }

  protected[strober] def peekChannel(addr: Int) = {
    while(!_peek(outs(addr).valid)) takeStep
    val value = _peek(outs(addr).bits)
    _poke(outs(addr).ready, 1)
    takeStep
    _poke(outs(addr).ready, 0)
    value
  }

  protected[strober] def readSnapshot = {
    val snap = new StringBuilder
    ChainType.values.toList foreach { t =>
      for (k <- 0 until chainLoop(t) ; i <- 0 until chainLen(t)) {
        if (t == ChainType.SRAM) _poke(c.io.daisy.sram.restart, 1)
        while(!_peek(c.io.daisy(t).out.valid)) takeStep
        if (t == ChainType.SRAM) _poke(c.io.daisy.sram.restart, 0)
        snap append intToBin(_peek(c.io.daisy(t).out.bits), c.daisyWidth)
        _poke(c.io.daisy(t).out.ready, 1)
        takeStep
        _poke(c.io.daisy(t).out.ready, 0)
      }
    }
    snap.result
  }

  override def setTraceLen(len: Int) {
    super.setTraceLen(len)
    while (!_peek(c.io.traceLen.ready)) takeStep
    _poke(c.io.traceLen.bits, len)
    _poke(c.io.traceLen.valid, 1)
    takeStep
    _poke(c.io.traceLen.valid, 0)
  }

  protected[strober] def _tick(n: Int) {
    for (i <- 0 until n) {
      inMap foreach {case (in, id) => pokeId(id, chunk(in), pokeMap getOrElse (id, BigInt(0)))}
      peekMap.clear
      outMap foreach {case (out, id) => peekMap(id) = peekId(id, chunk(out))}
    }
  }

  override def reset(n: Int) {
    // tail samples
    lastSample match {
      case None =>
      case Some((sample, id)) => samples(id) = traces(sample)
    }
    lastSample = None
    super.reset(n)
    flush
    t = 0
  }

  super.setTraceLen(c.traceMaxLen)
  flush
}

abstract class NASTIShimTester[+T <: NASTIShim[SimNetwork]](c: T, isTrace: Boolean = true, snapCheck: Boolean = true) extends SimTester(c, isTrace, snapCheck) {
  protected[strober] val inMap = c.master.inMap
  protected[strober] val outMap = c.master.outMap
  protected[strober] val inTrMap = c.master.inTrMap
  protected[strober] val outTrMap = c.master.outTrMap
  protected[strober] def chunk(wire: Bits) = c.sim.io.chunk(wire)
  protected[strober] val sampleNum = c.sim.sampleNum
  protected[strober] val channelOff = log2Up(c.sim.channelWidth)
 
  private val mem = Array.fill(1<<24){0.toByte} // size = 16MB
  private val lineOffset = log2Up(c.slave.lineSize)
  private val addrOffset = log2Up(c.master.nastiXAddrBits/8)

  protected[strober] def pokeChannel(addr: Int, data: BigInt) {
    do {
      _poke(c.io.mnasti.aw.bits.addr, addr << addrOffset)
      _poke(c.io.mnasti.aw.bits.id, 0)
      _poke(c.io.mnasti.aw.valid, 1)
      _poke(c.io.mnasti.w.bits.data, data)
      _poke(c.io.mnasti.w.valid, 1)
      takeStep
    } while (!_peek(c.io.mnasti.aw.ready) || !_peek(c.io.mnasti.w.ready))

    do {
      _poke(c.io.mnasti.aw.valid, 0)
      _poke(c.io.mnasti.w.valid, 0)
      takeStep
    } while(!_peek(c.io.mnasti.b.valid))

    assert(_peek(c.io.mnasti.b.bits.id) == 0)
    _poke(c.io.mnasti.b.ready, 1)
  }

  protected[strober] def peekChannel(addr: Int) = {
    while (!_peek(c.io.mnasti.ar.ready)) takeStep

    _poke(c.io.mnasti.ar.bits.addr, addr << addrOffset)
    _poke(c.io.mnasti.ar.bits.id, 0)
    _poke(c.io.mnasti.ar.valid, 1)
    takeStep
    _poke(c.io.mnasti.ar.valid, 0)
 
    while (!_peek(c.io.mnasti.r.valid)) takeStep
      
    val data = _peek(c.io.mnasti.r.bits.data)
    assert(_peek(c.io.mnasti.r.bits.id) == 0)
    _poke(c.io.mnasti.r.ready, 1)
    takeStep
    _poke(c.io.mnasti.r.ready, 0)
    data
  }

  def readMem(addr: BigInt) = {
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.addr), addr >> lineOffset)
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.tag), 0)
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.rw), 0)
    do { takeStep } while (!_peek(c.io.snasti.ar.valid))
    tickMem

    assert(peekChannel(c.master.respMap(c.mem.resp.bits.tag)) == 0)
    val id = c.master.respMap(c.mem.resp.bits.data)
    val data = Array.fill(c.mifDataBeats){BigInt(0)}
    for (i <- 0 until c.mifDataBeats) {
      data(i) = peekId(id, chunk(c.mem.resp.bits.data))
      if (isTrace) println("[MEM READ] addr: %x, data: %x".format(addr, data(i)))
    }
    data
  }

  def writeMem(addr: BigInt, data: Array[BigInt]) {
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.addr), addr >> lineOffset)
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.tag), 0)
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.rw), 1)
    val id = c.master.reqMap(c.mem.req_data.bits.data)
    for (i <- 0 until c.mifDataBeats) {
      if (isTrace) println("[MEM WRITE] addr: %x, data: %x".format(addr+(i*c.mifDataBits/8), data(i)))
      pokeId(id, chunk(c.mem.req_data.bits.data), data(i))
      if (i == 0) {
        do { takeStep } while (!_peek(c.io.snasti.aw.valid))
        tickMem
      }
      for (j <- 0 until c.slave.nastiDataBeats) {
        do { takeStep } while (!_peek(c.io.snasti.w.valid))
        tickMem
      }
    }
  } 

  case class MemWriteInfo(aw: Int, len: Int, size: Int, k: Int)
  private var wr_info: Option[MemWriteInfo] = None
  private def tickMem = wr_info match {
    case Some(MemWriteInfo(aw, len, size, k)) if _peek(c.io.snasti.w.valid) =>
      // handle write data
      val data = _peek(c.io.snasti.w.bits.data)
      if (isTrace) println("[TICK MEM WRITE] addr: %x, data: %x".format(aw+k*size, data))
      (0 until size) foreach (i => mem(aw+k*size+i) = ((data >> (8*i)) & 0xff).toByte)
      _poke(c.io.snasti.w.ready, 1)
      val last = _peek(c.io.snasti.w.bits.last)
      takeStep
      _poke(c.io.snasti.w.ready, 0)
      assert(k < len || last != 0 && k == len)
      wr_info = if (last) None else Some(new MemWriteInfo(aw, len, size, k+1))
    case None if _peek(c.io.snasti.ar.valid) =>
      // handle read address
      val ar = _peek(c.io.snasti.ar.bits.addr).toInt & 0xffffff
      val tag = _peek(c.io.snasti.ar.bits.id)
      val len = _peek(c.io.snasti.ar.bits.len).toInt
      val size = 1 << _peek(c.io.snasti.ar.bits.size).toInt
      _poke(c.io.snasti.ar.ready, 1)
      do { takeStep } while (!_peek(c.io.snasti.r.ready))
      _poke(c.io.snasti.ar.ready, 0)
      // handle read data
      for (k <- 0 to len) {
        val data = ((0 until size) foldLeft BigInt(0))((res, i) => 
          res | BigInt(mem(ar+k*size+i) & 0xff) << (8*i))
        if (isTrace) println("[TICK MEM READ] addr: %x, data: %x".format(ar+k*size, data))
        _poke(c.io.snasti.r.bits.data, data)
        _poke(c.io.snasti.r.bits.id, tag)
        _poke(c.io.snasti.r.bits.last, 1)
        _poke(c.io.snasti.r.valid, 1)
        do { takeStep } while (!_peek(c.io.snasti.r.ready))
        _poke(c.io.snasti.r.bits.last, 0)
        _poke(c.io.snasti.r.valid, 0)
      }
    case None if _peek(c.io.snasti.aw.valid) =>
      // handle write address
      wr_info = Some(new MemWriteInfo(
        _peek(c.io.snasti.ar.bits.addr).toInt & 0xffffff,
        _peek(c.io.snasti.aw.bits.len).toInt,
        1 << _peek(c.io.snasti.aw.bits.size).toInt, 0))
      _poke(c.io.snasti.aw.ready, 1)
      takeStep
      _poke(c.io.snasti.aw.ready, 0)
    case _ =>
      _poke(c.io.snasti.ar.ready, 0)
      _poke(c.io.snasti.r.bits.last, 0)
      _poke(c.io.snasti.r.valid, 0)
      _poke(c.io.snasti.aw.ready, 0)
      _poke(c.io.snasti.w.ready, 0)
  }

  private def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def loadMem(filename: String) {
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      (((line.length - 2) to 0 by -2) foldLeft 0){(offset, k) =>
        mem(base+offset) = ((parseNibble(line(k)) << 4) | parseNibble(line(k+1))).toByte
        offset + 1
      }
    }
  }

  def slowLoadMem(filename: String) {
    println("[LOADMEM] LOADING " + filename)
    val step = c.mifDataBits / 4
    val data = Array.fill(c.mifDataBeats){BigInt(0)}
    val lines = Source.fromFile(filename).getLines
    (lines foldLeft (0, 0)){case ((base_idx, base_addr), line) =>
      (((line.length - step) to 0 by -step) foldLeft (base_idx, base_addr)){case ((i, addr), j) =>
        data(i % c.mifDataBeats) = ((0 until step) foldLeft BigInt(0)){case (res, k) =>
          res | BigInt(parseNibble(line(j+k))) << (4*(step-1-k))}
        if ((i + 1) % c.mifDataBeats == 0) {
          writeMem(addr, data)
          (i + 1, addr + step*c.mifDataBeats/2)
        } else {
          (i + 1, addr)
        }
      }
    }
    println("[LOADMEM] DONE ")
  }

  protected[strober] def readSnapshot = {
    val snap = new StringBuilder
    ChainType.values.toList foreach { t =>
      for (k <- 0 until chainLoop(t)) {
        if (t == ChainType.SRAM) pokeChannel(c.master.sramRestartAddr, 0)
        for (i <- 0 until chainLen(t)) {
          snap append intToBin(peekChannel(c.master.snapOutMap(t)), c.sim.daisyWidth)
        }
      }
    }
    snap.result
  }

  override def setTraceLen(len: Int) { 
    super.setTraceLen(len)
    pokeChannel(c.master.traceLenAddr, len)
  }

  def setMemCycles(cycles: Int) {
    pokeChannel(c.master.memCycleAddr, cycles)
  }

  protected[strober] def _tick(n: Int) {
    pokeChannel(0, n)
    inMap foreach {case (in, id) => pokeId(id, chunk(in), pokeMap getOrElse (id, BigInt(0)))}
    while (!peekChannel(0)) tickMem
    (0 until 5) foreach (_ => takeStep)
    tickMem // handle tail requests
    peekMap.clear
    outMap foreach {case (out, id) => peekMap(id) = peekId(id, chunk(out))}
  }

  override def reset(n: Int) {
    for (_ <- 0 until n) {
      super.reset(1)
      pokeChannel(c.master.resetAddr, 0)
    }
    flush
    t = 0
  }

  super.setTraceLen(c.sim.traceMaxLen)
  for (_ <- 0 until 5) {
    super.reset(1)
    pokeChannel(c.master.resetAddr, 0)
  }
  flush
}
