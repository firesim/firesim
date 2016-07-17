package strober

import chisel3._
import scala.collection.immutable.ListMap
import scala.collection.mutable.{HashMap, ArrayBuffer, Queue => ScalaQueue}

abstract class SimWrapperTester[+T <: Module](c: SimWrapper[T], verbose: Boolean=true,
    logFile: Option[String]=None, waveform: Option[String]=None, testCmd: List[String]=Nil)
    extends StroberTester(c, verbose, logFile, waveform, testCmd) {
  protected[strober] val _inputs = 
    (c.io.inputs map {case (x, y) => x -> s"${c.target.name}.$y"}).toMap
  protected[strober] val _outputs = 
    (c.io.outputs map {case (x, y) => x -> s"${c.target.name}.$y"}).toMap
  protected[strober] val inTrMap = c.io.inTrMap
  protected[strober] val outTrMap = c.io.outTrMap
  protected[strober] implicit val channelWidth = c.channelWidth
  // protected[strober] val sampleNum = c.sampleNum

  private val ins = c.io.ins map (
    in => ChannelSource(in))
  private val outs = (c.io.outs ++ c.io.inT ++ c.io.outT) map (
    out => ChannelSink(out))

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

  protected[strober] def _tick(n: Int) {
    for (i <- 0 until n) {
      c.io.inMap foreach {case (in, id) => 
        pokeChunks(id, SimUtils.getChunks(in), _pokeMap getOrElse (in, BigInt(rnd.nextInt)))
      }
    }
    _peekMap.clear
    for (i <- 0 until n) {
      c.io.outMap foreach {case (out, id) =>
        _peekMap(out) = peekChunks(id, SimUtils.getChunks(out))
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

  override def setTraceLen(len: Int) {
    super.setTraceLen(len)
    _poke(c.io.traceLen, len)
  }

  reset(5)
  super.setTraceLen(c.traceMaxLen)
}
