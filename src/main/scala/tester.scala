package strober

import Chisel._
import Chisel.iotesters.{AdvTester, Processable}
import scala.collection.immutable.ListMap
import scala.collection.mutable.{HashMap, ArrayBuffer, Queue => ScalaQueue}

abstract class SimTester[+T <: Module](c: T, verbose: Boolean) extends AdvTester(c, false) {
  protected[strober] val _pokeMap = HashMap[Data, BigInt]()
  protected[strober] val _peekMap = HashMap[Data, BigInt]()
  protected[strober] def _inputs: Map[Bits, String]
  protected[strober] def _outputs: Map[Bits, String]
  protected[strober] def inTrMap: ListMap[Bits, Int]
  protected[strober] def outTrMap: ListMap[Bits, Int]
  protected[strober] implicit val channelWidth: Int
  private var traceCount = 0

  // protected[strober] lazy val chainLoop = transforms.chainLoop
  // protected[strober] lazy val chainLen  = transforms.chainLen

  // protected[strober] def sampleNum: Int
  // protected[strober] lazy val samples = Array.fill(sampleNum){new Sample}
  // protected[strober] var lastSample: Option[(Sample, Int)] = None
  private var _traceLen = 0
  def traceLen = _traceLen
  implicit def bigintToBoolean(b: BigInt) = b != 0

  private val _preprocessors = ArrayBuffer[Processable]()
  private val _postprocessors = ArrayBuffer[Processable]()

  protected[strober] class ChannelSource[T <: Bits](socket: DecoupledIO[T]) extends Processable {
    val inputs = new ScalaQueue[BigInt]()
    private var valid = false
    def process {
      if (valid && _peek(socket.ready)) {
        valid = false
      }
      if (!valid && !inputs.isEmpty) {
        valid = true
        _poke(socket.bits, inputs.dequeue)
      }
      _poke(socket.valid, valid)
    }
    _preprocessors += this
  }

  protected[strober] class ChannelSink[T <: Bits](socket: DecoupledIO[T]) extends Processable {
    val outputs = new ScalaQueue[BigInt]()
    def process {
      if (_peek(socket.valid)) {
        outputs enqueue _peek(socket.bits)
      }
      _poke(socket.ready, true)
    }
    _postprocessors += this
  }

  protected[strober] def pokeChannel(addr: Int, data: BigInt): Unit
  protected[strober] def peekChannel(addr: Int): BigInt
  protected[strober] def pokeChunk(addr: Int, chunks: Int, data: BigInt) {
    (0 until chunks) foreach (off => pokeChannel(addr + off, data >> (off * channelWidth)))
  }
  protected[strober] def peekChunk(addr: Int, chunks: Int) = {
    ((0 until chunks) foldLeft BigInt(0))(
      (res, off) => res | (peekChannel(addr + off) << (off * channelWidth)))
  }
  protected[strober] def _poke(data: Bits, x: BigInt) = super.wire_poke(data, x)
  protected[strober] def _peek(data: Bits) = super.peek(data)
  protected[strober] def _takestep(work: => Unit = {}) {
    emulator.step(1)
    _preprocessors foreach (_.process)
    work
    _postprocessors foreach (_.process)
  }
  protected[strober] def _until(pred: => Boolean, 
      maxcycles: Long = defaultMaxCycles)(work: => Unit) = {
    var cycle = 0
    while (!pred && cycle < maxcycles) {
      _takestep(work)
      cycle += 1
    }
    pred
  }
  protected[strober] def _eventually(pred: => Boolean,
      maxcycles: Long = defaultMaxCycles) = {
    _until(pred, maxcycles){}
  }

  override def wire_poke(port: Bits, value: BigInt) = this.poke(port, value)

  override def poke(port: Bits, value: BigInt) {
    require(_inputs contains port)
    if (verbose) println(s"  POKE ${_inputs(port)} <- %x".format(value))
    _pokeMap(port) = value
  }
 
  override def peek(port: Bits) = {
    require(_outputs contains port)
    val value = _peekMap getOrElse (port, BigInt(rnd.nextInt))
    if (verbose) println(s"  PEEK ${_outputs(port)} -> %x".format(value))
    value
  }

  override def expect(pass: Boolean, msg: => String): Boolean = {
    if (verbose) println(s"  EXPECT ${msg}: %s".format(if (pass) "PASS" else "FAIL"))
    if (!pass) fail
    pass
  }
 
  override def expect(port: Bits, expected: BigInt, msg: => String = ""): Boolean = {
    require(_outputs contains port)
    val value = _peekMap getOrElse (port, BigInt(rnd.nextInt))
    expect(value == expected, s"${msg} ${_outputs(port)} -> %x == %x".format(value, expected))
  }

  /* protected[strober] def traces(sample: Sample) = {
    for (i <- 0 until traceCount) {
      inTrMap foreach {case (wire, id) => sample addCmd PokePort(wire, peekId(id, chunk(wire)))}
      sample addCmd Step(1)
      outTrMap foreach {case (wire, id) => sample addCmd ExpectPort(wire, peekId(id, chunk(wire)))}
    }
    sample
  }

  protected def intToBin(value: BigInt, size: Int) =
    ((0 until size) map (i => (((value >> (size-1-i)) & 0x1) + '0').toChar) addString new StringBuilder).result

  protected[strober] def readChain(t: ChainType.Value): String
  protected[strober] def readSnapshot = {
    (ChainType.values.toList map readChain addString new StringBuilder).result
  }
  
  protected[strober] def verifySnapshot(sample: Sample) {
    val pass = (sample map {
      case Load(signal: MemRead,    value, None) => true 
      case Load(signal: MemSeqRead, value, None) => true
      case Load(signal, value, off) => 
        val expected = peekNode(signal, off) 
        expect(expected == value, "%s%s -> %x == %x".format(transforms.nameMap(signal),
          off map (x => s"[${x}]") getOrElse "", expected, value))
      case Force(signal, value) =>
        val expected = peekNode(signal, None)
        expect(expected == value, "%s -> %x == %x".format(transforms.nameMap(signal),
          expected, value))
      case _ => true   
    } foldLeft true)(_ && _)
    addEvent(new ExpectMsgEvent(pass, "* SNAPSHOT : "))
  }
  */

  protected[strober] def _tick(n: Int): Unit

  def setTraceLen(len: Int) {
    _traceLen = len
  }

  override def step(n: Int) {
    if (verbose) println(s"STEP ${n} -> ${t+n}")
    // reservoir sampling
    /* if (cycles % traceLen == 0) {
      val recordId = t / traceLen
      val sampleId = if (recordId < sampleNum) recordId else rnd.nextInt(recordId+1)
      if (sampleId < sampleNum) {
        lastSample match {
          case None =>
          case Some((sample, id)) => samples(id) = traces(sample)
        }
        val sample = Sample(readSnapshot, cycles)
        lastSample = Some((sample, sampleId)) 
        if (args.snapCheck) verifySnapshot(sample)
        traceCount = 0 
      }
    } */
    // take steps
    _tick(n)
    incTime(n)
    if (traceCount < traceLen) traceCount += n
  }

  /* override def finish = {
    // tail samples
    lastSample match {
      case None =>
      case Some((sample, id)) => samples(id) = traces(sample)
    }
    val file = args.sampleFile match {
      case None => createOutputFile(s"${transforms.targetName}.sample")
      case Some(f) => new java.io.FileWriter(f)
    }
    try {
      file write (samples filter (_.cycle >= 0) map (_.toString) mkString "")
      file write Sample(readSnapshot, t).toString
    } finally {
      file.close
    }
    super.finish
  } */
}

abstract class SimWrapperTester[+T <: SimWrapper[Module]](c: T,
    verbose: Boolean = true) extends SimTester(c, verbose) {
  protected[strober] val _inputs = 
    (c.io.inputs map {case (x, y) => x -> s"${c.target.name}.$y"}).toMap
  protected[strober] val _outputs = 
    (c.io.outputs map {case (x, y) => x -> s"${c.target.name}.$y"}).toMap
  protected[strober] val inMap = c.io.inMap 
  protected[strober] val outMap = c.io.outMap
  protected[strober] val inTrMap = c.io.inTrMap
  protected[strober] val outTrMap = c.io.outTrMap
  protected[strober] implicit val channelWidth = c.channelWidth
  // protected[strober] val sampleNum = c.sampleNum

  private val ins = c.io.ins map (
    in => new ChannelSource(in))
  private val outs = (c.io.outs ++ c.io.inT ++ c.io.outT) map (
    out => new ChannelSink(out))

  protected[strober] def pokeChannel(addr: Int, data: BigInt) {
    ins(addr).inputs enqueue data
  }

  protected[strober] def peekChannel(addr: Int) = {
    _eventually(!outs(addr).outputs.isEmpty)
    outs(addr).outputs.dequeue
  }

  /* protected[strober] def readChain(t: ChainType.Value) = {
    addEvent(new MuteEvent())
    val chain = new StringBuilder
    for (k <- 0 until chainLoop(t) ; i <- 0 until chainLen(t)) {
      t match {
        case ChainType.SRAM0 => _poke(c.io.daisy.sram(0).restart, 1)
        case ChainType.SRAM1 => _poke(c.io.daisy.sram(1).restart, 1)
        case _ =>
      }
      while(!_peek(c.io.daisy(t).out.valid)) takeStep
      t match {
        case ChainType.SRAM0 => _poke(c.io.daisy.sram(0).restart, 0)
        case ChainType.SRAM1 => _poke(c.io.daisy.sram(1).restart, 0)
        case _ =>
      }
      chain append intToBin(_peek(c.io.daisy(t).out.bits), c.daisyWidth)
      _poke(c.io.daisy(t).out.ready, 1)
      takeStep
      _poke(c.io.daisy(t).out.ready, 0)
    }
    addEvent(new UnmuteEvent())
    chain.result
  } */

  /*
  override def setTraceLen(len: Int) {
    addEvent(new MuteEvent())
    super.setTraceLen(len)
    while (!_peek(c.io.traceLen.ready)) takeStep
    _poke(c.io.traceLen.bits, len)
    _poke(c.io.traceLen.valid, 1)
    takeStep
    _poke(c.io.traceLen.valid, 0)
    addEvent(new UnmuteEvent())
  }
  */

  protected[strober] def _tick(n: Int) {
    for (i <- 0 until n) {
      inMap foreach {case (in, id) => 
        pokeChunk(id, SimWrapper.getChunk(in), _pokeMap getOrElse (in, BigInt(rnd.nextInt)))
      }
    }
    _peekMap.clear
    for (i <- 0 until n) {
      outMap foreach {case (out, id) =>
        _peekMap(out) = peekChunk(id, SimWrapper.getChunk(out))
      }
    }
  }

  override def reset(n: Int) {
    // tail samples
    /* lastSample match {
      case None =>
      case Some((sample, id)) => samples(id) = traces(sample)
    }
    lastSample = None */
    _pokeMap(c.target.reset) = 1
    _tick(n)
    _pokeMap(c.target.reset) = 0
  }

  super.setTraceLen(c.traceMaxLen)
  reset(5)
}

// Data type to specify loadmem alg.
/*
trait LoadMemType
case object FastLoadMem extends LoadMemType
case object SlowLoadMem extends LoadMemType

abstract class NastiShimTester[+T <: NastiShim[SimNetwork]](c: T, 
    args: StroberTestArgs, loadmemType: LoadMemType = FastLoadMem) 
    extends SimTester(c, args) {
  protected[strober] val inMap = c.master.inMap
  protected[strober] val outMap = c.master.outMap
  protected[strober] val inTrMap = c.master.inTrMap
  protected[strober] val outTrMap = c.master.outTrMap
  protected[strober] def chunk(wire: Bits) = c.sim.io.chunk(wire)
  protected[strober] val sampleNum = c.sim.sampleNum
  protected[strober] val channelOff = log2Up(c.sim.channelWidth)
 
  private val mem = Array.fill(1<<24){0.toByte} // size = 16MB
  private val addrOffset = log2Up(c.master.nastiXAddrBits/8)

  protected[strober] def pokeChannel(addr: Int, data: BigInt) {
    addEvent(new MuteEvent())
    do {
      _poke(c.io.master.aw.bits.addr, addr << addrOffset)
      _poke(c.io.master.aw.bits.id, 0)
      _poke(c.io.master.aw.valid, 1)
      _poke(c.io.master.w.bits.data, data)
      _poke(c.io.master.w.valid, 1)
      takeStep
    } while (!_peek(c.io.master.aw.ready) || !_peek(c.io.master.w.ready))

    do {
      _poke(c.io.master.aw.valid, 0)
      _poke(c.io.master.w.valid, 0)
      takeStep
    } while(!_peek(c.io.master.b.valid))

    assert(_peek(c.io.master.b.bits.id) == 0)
    _poke(c.io.master.b.ready, 1)
    addEvent(new UnmuteEvent())
  }

  protected[strober] def peekChannel(addr: Int) = {
    addEvent(new MuteEvent())
    while (!_peek(c.io.master.ar.ready)) takeStep

    _poke(c.io.master.ar.bits.addr, addr << addrOffset)
    _poke(c.io.master.ar.bits.id, 0)
    _poke(c.io.master.ar.valid, 1)
    takeStep
    _poke(c.io.master.ar.valid, 0)
 
    while (!_peek(c.io.master.r.valid)) takeStep
      
    val data = _peek(c.io.master.r.bits.data)
    assert(_peek(c.io.master.r.bits.id) == 0)
    _poke(c.io.master.r.ready, 1)
    takeStep
    _poke(c.io.master.r.ready, 0)
    addEvent(new UnmuteEvent())
    data
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
  }

  super.setTraceLen(c.sim.traceMaxLen)
  for (_ <- 0 until 5) {
    super.reset(1)
    pokeChannel(c.master.resetAddr, 0)
  }
  flush
}
*/
