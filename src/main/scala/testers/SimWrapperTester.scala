package strober
package testers

import scala.collection.immutable.ListMap
import scala.collection.mutable.{HashMap, ArrayBuffer, Queue => ScalaQueue}

abstract class SimWrapperTester[+T <: chisel3.Module](c: SimWrapper[T], verbose: Boolean=true,
    sampleFile: Option[String]=None, logFile: Option[String]=None, waveform: Option[String]=None,
    testCmd: List[String]=Nil) extends StroberTester(c, verbose, sampleFile, logFile, waveform, testCmd) {
  private val ins = c.io.ins map (in => ChannelSource(in))
  private val outs = (c.io.outs ++ c.io.inT ++ c.io.outT) map (out => ChannelSink(out))
  private val inT = c.io.inT map (tr => ChannelSink(tr))
  private val outT = c.io.outT map (tr => ChannelSink(tr))

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
          _eventually(_peek(c.io.daisy(t)(i).out.valid))
      }
      for (_ <- 0 until chainLen(t)) {
        chain append intToBin(_peek(c.io.daisy(t)(i).out.bits), daisyWidth)
        _poke(c.io.daisy(t)(i).out.ready, 1)
        backend.step(1)
        _poke(c.io.daisy(t)(i).out.ready, 0)
      }
      assert(!_peek(c.io.daisy(t)(i).out.valid))
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
    _pokeMap(c.target.reset) = 1
    _tick(n)
    _pokeMap(c.target.reset) = 0
    // flush junk traces
    super.reset(n)
  }

  override def setTraceLen(len: Int) {
    super.setTraceLen(len)
    _poke(c.io.traceLen, len)
  }

  reset(5)
}
