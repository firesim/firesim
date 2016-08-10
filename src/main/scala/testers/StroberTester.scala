package strober
package testers

import chisel3.{Data, Bits}
import chisel3.util.DecoupledIO
import chisel3.iotesters.{AdvTester, Processable}
import java.io.{File, FileWriter}
import scala.collection.immutable.ListMap
import scala.collection.mutable.{HashMap, ArrayBuffer, Queue => ScalaQueue}

abstract class StroberTester[+T <: chisel3.Module](c: T, verbose: Boolean, sampleFile: Option[String],
    logFile: Option[String], waveform: Option[String], testCmd: List[String])
    extends AdvTester(c, false, 16, logFile, waveform, testCmd, false) {
  protected[testers] val _pokeMap = HashMap[Data, BigInt]()
  protected[testers] val _peekMap = HashMap[Data, BigInt]()
  private val sim = c match {
    case s: SimWrapper[_] => s
    case s: ZynqShim[_] => s.sim match { case s: SimWrapper[_] => s }
  }
  private val targetName = sim.target.name
  private val _inputs = (sim.io.inputs map {case (x, y) => x -> s"$targetName.$y"}).toMap
  private val _outputs = (sim.io.outputs map {case (x, y) => x -> s"$targetName.$y"}).toMap
  private val (inTrMap, outTrMap) = c match {
    case s: SimWrapper[_] => (s.io.inTrMap, s.io.outTrMap)
    case s: ZynqShim[_] => (s.IN_TR_ADDRS, s.OUT_TR_ADDRS)
  }
  protected[testers] val daisyWidth = sim.daisyWidth
  protected[testers] implicit val channelWidth = sim.channelWidth
  protected[testers] val chainLen = StroberCompiler.context.chainLen
  protected[testers] val chainLoop = StroberCompiler.context.chainLoop.toMap
  protected[testers] val chainReader = new DaisyChainReader(
    new File(StroberCompiler.context.dir, s"${targetName}.chain"), chainLoop, daisyWidth)

  private val sampleNum = StroberCompiler.context.sampleNum
  private val samples = Array.fill(sampleNum){new Sample}
  private var lastSample: Option[(Sample, Int)] = None
  private var _traceLen = sim.traceMaxLen
  def traceLen = _traceLen
  implicit def bigintToBoolean(b: BigInt) = b != 0

  protected[testers] val _preprocessors = ArrayBuffer[Processable]()
  protected[testers] val _postprocessors = ArrayBuffer[Processable]()

  protected[testers] class ChannelSource[T <: Data, R](
    socket: DecoupledIO[T], post: (T, R) => Unit) extends Processable {
    val inputs = new ScalaQueue[R]()
    private var valid = false
    def process {
      if (valid && _peek(socket.ready)) {
        valid = false
      }
      if (!valid && !inputs.isEmpty) {
        valid = true
        post(socket.bits, inputs.dequeue)
      }
      _poke(socket.valid, valid)
    }
    _preprocessors += this
  }
  protected[testers] object ChannelSource {
    def apply[T <: Bits](socket: DecoupledIO[T]) =
      new ChannelSource(socket, (bit: T, in: BigInt) => _poke(bit, in))
  }

  protected[testers] class ChannelSink[T <: Data, R](
    socket: DecoupledIO[T], cvt: T => R) extends Processable {
    val outputs = new ScalaQueue[R]()
    def process {
      if (_peek(socket.valid)) {
        outputs enqueue cvt(socket.bits)
      }
      _poke(socket.ready, true)
    }
    _postprocessors += this
  }
  protected[testers] object ChannelSink {
    def apply[T <: Bits](socket: DecoupledIO[T]) =
      new ChannelSink(socket, (bit: T) => _peek(bit))
  }

  protected[testers] def pokeChannel(addr: Int, data: BigInt): Unit
  protected[testers] def peekChannel(addr: Int): BigInt
  protected[testers] def pokeChunks(addr: Int, chunks: Int, data: BigInt) {
    (0 until chunks) foreach (off => pokeChannel(addr + off, data >> (off * channelWidth)))
  }
  protected[testers] def peekChunks(addr: Int, chunks: Int) = {
    ((0 until chunks) foldLeft BigInt(0))(
      (res, off) => res | (peekChannel(addr + off) << (off * channelWidth)))
  }
  protected[testers] def _poke(data: Bits, x: BigInt) = super.wire_poke(data, x)
  protected[testers] def _peek(data: Bits) = super.peek(data)
  protected[testers] def _takestep(work: => Unit = {}) {
    backend.step(1)
    _preprocessors foreach (_.process)
    work
    _postprocessors foreach (_.process)
  }
  protected[testers] def _until(pred: => Boolean, 
      maxcycles: Long = defaultMaxCycles)(work: => Unit) = {
    var cycle = 0
    while (!pred && cycle < maxcycles) {
      _takestep(work)
      cycle += 1
    }
    pred
  }
  protected[testers] def _eventually(pred: => Boolean,
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

  protected def intToBin(value: BigInt, size: Int) =
    ((0 until size) map (i => (((value >> (size-1-i)) & 0x1) + '0').toChar) addString new StringBuilder).result

  protected[testers] def readChain(t: ChainType.Value): String
  protected[testers] def readSnapshot = {
    (ChainType.values.toList map readChain addString new StringBuilder).result
  }
  protected[testers] def readTraces(sample: Sample, n: Int) = {
    for (i <- 0 until n) {
      inTrMap foreach {case (in, id) =>
        sample addCmd PokePort(_inputs(in), peekChunks(id, SimUtils.getChunks(in)))
      }
      sample addCmd Step(1)
      outTrMap foreach {case (out, id) =>
        sample addCmd ExpectPort(_outputs(out), peekChunks(id, SimUtils.getChunks(out)))
      }
    }
    sample
  }

  /*
  protected[testers] def verifySnapshot(sample: Sample) {
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

  protected[testers] def _tick(n: Int): Unit

  def setTraceLen(len: Int) {
    _traceLen = len
  }

  def setLastSample(s: Option[(Sample, Int)], count: Int) {
    lastSample match {
      case None =>
        val _ = readTraces(new Sample, count)
      case Some((sample, id)) =>
        samples(id) = readTraces(sample, count)
    }
    lastSample = s
  }

  private var traceCount = 0
  override def step(n: Int) {
    if (verbose) println(s"STEP ${n} -> ${t+n}")
    // reservoir sampling
    if (cycles % traceLen == 0) {
      val recordId = cycles / traceLen
      val sampleId = if (recordId < sampleNum) recordId else rnd.nextInt(recordId+1)
      if (sampleId < sampleNum) {
        val sample = chainReader(readSnapshot, cycles)
        setLastSample(Some(sample, sampleId), traceCount)
        // if (args.snapCheck) verifySnapshot(sample)
        traceCount = 0 
      }
    }
    // take steps
    _tick(n)
    incTime(n)
    if (traceCount < traceLen) traceCount += n
  }

  override def reset(n: Int) {
    // flush junk traces
    setLastSample(None, n)
  }

  override def finish = {
    setLastSample(None, traceCount)
    val file = sampleFile match {
      case None => new FileWriter(
        new File(StroberCompiler.context.dir, s"$targetName.sample"))
      case Some(f) => new FileWriter(f)
    }
    try {
      file write (samples filter (_.cycle >= 0) map (_.toString) mkString "")
      file write chainReader(readSnapshot, cycles).toString
    } finally {
      file.close
    }
    super.finish
  }
}
