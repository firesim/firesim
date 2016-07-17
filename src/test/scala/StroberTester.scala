package strober

import chisel3._
import chisel3.util._
import chisel3.iotesters.{AdvTester, Processable, Backend}
import scala.collection.immutable.ListMap
import scala.collection.mutable.{HashMap, ArrayBuffer, Queue => ScalaQueue}

abstract class StroberTester[+T <: Module](c: T, verbose: Boolean,
    logFile: Option[String], waveform: Option[String], testCmd: List[String])
    extends AdvTester(c, false, logFile=logFile, waveform=waveform, testCmd=testCmd, isPropagation=false) {
  protected[strober] val _pokeMap = HashMap[Data, BigInt]()
  protected[strober] val _peekMap = HashMap[Data, BigInt]()
  protected[strober] def _inputs: Map[Bits, String]
  protected[strober] def _outputs: Map[Bits, String]
  // protected[strober] def inTrMap: ListMap[Bits, Int]
  // protected[strober] def outTrMap: ListMap[Bits, Int]
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

  protected[strober] val _preprocessors = ArrayBuffer[Processable]()
  protected[strober] val _postprocessors = ArrayBuffer[Processable]()

  protected[strober] class ChannelSource[T <: Data, R](
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
  protected[strober] object ChannelSource {
    def apply[T <: Bits](socket: DecoupledIO[T]) =
      new ChannelSource(socket, (bit: T, in: BigInt) => _poke(bit, in))
  }

  protected[strober] class ChannelSink[T <: Data, R](
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
  protected[strober] object ChannelSink {
    def apply[T <: Bits](socket: DecoupledIO[T]) =
      new ChannelSink(socket, (bit: T) => _peek(bit))
  }

  protected[strober] def pokeChannel(addr: Int, data: BigInt): Unit
  protected[strober] def peekChannel(addr: Int): BigInt
  protected[strober] def pokeChunks(addr: Int, chunks: Int, data: BigInt) {
    (0 until chunks) foreach (off => pokeChannel(addr + off, data >> (off * channelWidth)))
  }
  protected[strober] def peekChunks(addr: Int, chunks: Int) = {
    ((0 until chunks) foldLeft BigInt(0))(
      (res, off) => res | (peekChannel(addr + off) << (off * channelWidth)))
  }
  protected[strober] def _poke(data: Bits, x: BigInt) = super.wire_poke(data, x)
  protected[strober] def _peek(data: Bits) = super.peek(data)
  protected[strober] def _takestep(work: => Unit = {}) {
    backend.step(1)
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
