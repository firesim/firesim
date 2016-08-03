package strober
package testers

import scala.collection.immutable.ListMap
import scala.collection.mutable.{HashMap, ArrayBuffer, Queue => ScalaQueue}

abstract class SimWrapperTester[+T <: chisel3.Module](c: SimWrapper[T], meta: StroberMetaData,
    verbose: Boolean=true, sampleFile: Option[String] = None, logFile: Option[String]=None,
    waveform: Option[String]=None, testCmd: List[String]=Nil)
    extends StroberTester(c, meta, verbose, sampleFile, logFile, waveform, testCmd) {
  protected[testers] val _inputs = 
    (c.io.inputs map {case (x, y) => x -> s"${c.target.name}.$y"}).toMap
  protected[testers] val _outputs = 
    (c.io.outputs map {case (x, y) => x -> s"${c.target.name}.$y"}).toMap
  protected[testers] val inTrMap = c.io.inTrMap
  protected[testers] val outTrMap = c.io.outTrMap

  private val ins = c.io.ins map (
    in => ChannelSource(in))
  private val outs = (c.io.outs ++ c.io.inT ++ c.io.outT) map (
    out => ChannelSink(out))
  private val chains = (ChainType.values.toSeq map (chainType => chainType ->
    (c.io.daisy(chainType).toSeq map (chain => ChannelSink(chain.out))))).toMap

  protected[testers] def pokeChannel(addr: Int, data: BigInt) {
    ins(addr).inputs enqueue data
  }

  protected[testers] def peekChannel(addr: Int) = {
    _eventually(!outs(addr).outputs.isEmpty)
    outs(addr).outputs.dequeue
  }

  protected[testers] def readChain(t: ChainType.Value) = {
    val chain = new StringBuilder
    for (_ <- 0 until chainLoop(t) ; i <- 0 until c.io.daisy(t).size) {
      t match {
        case ChainType.SRAM =>
          _poke(c.io.daisy.sram(i).restart, 1)
          _eventually(_peek(c.io.daisy(t)(i).out.valid))
          _poke(c.io.daisy.sram(i).restart, 0)
        case _ =>
      }
      _eventually(chains(t)(i).outputs.size >= chainLen(t))
      while (!chains(t)(i).outputs.isEmpty) {
        chain append intToBin(chains(t)(i).outputs.dequeue, daisyWidth)
      }
    }
    chain.result
  }

  protected[testers] def _tick(n: Int) {
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
    setLastSample(None)
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
