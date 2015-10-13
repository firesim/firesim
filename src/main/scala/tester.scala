package strober

import Chisel._
import scala.collection.mutable.{HashMap, Queue => ScalaQueue, ArrayBuffer}
import scala.io.Source

abstract class SimTester[+T <: Module](c: T, isTrace: Boolean) extends Tester(c, false) {
  private val pokeMap = HashMap[Int, BigInt]()
  private val peekMap = HashMap[Int, BigInt]()
  private val inTraces = ArrayBuffer[ScalaQueue[BigInt]]()
  private val outTraces = ArrayBuffer[ScalaQueue[BigInt]]()
  private var traceCount = 0

  protected[strober] def inMap: Map[Bits, Int]
  protected[strober] def outMap: Map[Bits, Int]
  protected[strober] def inTrMap: Map[Bits, Int]
  protected[strober] def outTrMap: Map[Bits, Int]
  protected[strober] def chunk(wire: Bits): Int

  protected[strober] lazy val sampleNum = transforms.sampleNum
  protected[strober] lazy val channelOff = log2Up(transforms.channelWidth)
  protected[strober] lazy val traceLen = transforms.traceLen
  protected[strober] lazy val daisyWidth = transforms.daisyWidth
  protected[strober] lazy val regSnapLen = transforms.regSnapLen
  protected[strober] lazy val traceSnapLen = transforms.traceSnapLen
  protected[strober] lazy val sramSnapLen = transforms.sramSnapLen
  protected[strober] lazy val sramMaxSize = transforms.sramMaxSize

  protected[strober] lazy val samples = Array.fill(sampleNum){new Sample}
  protected[strober] var lastSample: Option[(Sample, Int)] = None

  protected[strober] def pokeChannel(addr: Int, data: BigInt): Unit
  protected[strober] def peekChannel(addr: Int): BigInt
  protected[strober] def pokePort(wire: Bits, id: Int, data: BigInt) {
    (0 until chunk(wire)) foreach (off => pokeChannel(id+off, data >> (off << channelOff)))
  }
  protected[strober] def peekPort(wire: Bits, id: Int) = {
    ((0 until chunk(wire)) foldLeft BigInt(0))(
      (res, off) => res | (peekChannel(id+off) << (off << channelOff)))
  }
  protected[strober] def _poke(data: Bits, x: BigInt) = super.poke(data, x)
  protected[strober] def _peek(data: Bits) = super.peek(data)

  override def poke(port: Bits, x: BigInt) {
    assert(inMap contains port)
    if (isTrace) println("* POKE " + dumpName(port) + " <- " + x.toString(16) + " *")
    pokeMap(inMap(port)) = x
  }
 
  override def peek(port: Bits) = {
    assert(outMap contains port)
    assert(peekMap contains outMap(port))
    val addr = outMap(port)
    val value = peekMap(outMap(port))
    if (isTrace) println("* PEEK " + dumpName(port) + " -> " + value.toString(16) + " *")
    value
  }

  override def expect(pass: Boolean, msg: String) = {
    if (isTrace) println(msg + (if (pass) " : PASS" else " : FAIL"))
    if (!pass && failureTime < 0) failureTime = t
    ok &= pass
    pass
  }
 
  override def expect(port: Bits, expected: BigInt) = {
    assert(outMap contains port)
    assert(peekMap contains outMap(port))
    val value = peekMap(outMap(port))
    val pass = value == expected 
    expect(pass, "* EXPECT " + dumpName(port) + " -> " + value.toString(16) + " == " + expected.toString(16))
  }

  protected[strober] def traces(sample: Sample) = {
    val len = inTraces(0).size  // can be less than traceLen, but same for all traces
    for (i <- 0 until len) {
      for ((wire, id) <- inMap.unzip._1.zipWithIndex) {
        val trace = inTraces(id)
        assert(i > 0 || trace.size == len, 
          "trace size: %d != trace len: %d".format(trace.size, len))
        sample addCmd PokePort(wire, trace.dequeue)
      }
      for ((wire, id) <- inTrMap) {
        sample addCmd PokePort(wire, peekPort(wire, id))
      }
      sample addCmd Step(1)
      for ((wire, id) <- outMap.unzip._1.zipWithIndex) {
        val trace = outTraces(id)
        assert(i > 0 || trace.size == len, 
          "trace size: %d != trace len: %d".format(trace.size, len))
        sample addCmd ExpectPort(wire, trace.dequeue)
      }
      for ((wire, id) <- outTrMap) {
        sample addCmd ExpectPort(wire, peekPort(wire, id))
      }
    }
    sample
  }

  protected def intToBin(value: BigInt, size: Int) = {
    var bin = ""
    for (i <- 0 until size) {
      bin += (((value >> (size-1-i)) & 0x1) + '0').toChar
    }
    bin
  }

  protected[strober] def readSnapshot: String

  protected[strober] def verifySnapshot(sample: Sample) = {
    val pass = (sample map {
      case Load(signal, value, off) => 
        val expected = peekNode(signal, off) 
        expect(expected == value, "%s%s -> %s == %s".format(transforms.nameMap(signal),
          off map ("[" + _ + "]") getOrElse "", expected.toString(16), value.toString(16)))
      case _ => true   
    }) reduce (_ && _)
    expect(pass, "* SNAPSHOT")
  }

  override def step(n: Int) {
    if (isTrace) println("STEP " + n + " -> " + (t + n))
    for (i <- 0 until n) {
      // reservoir sampling
      if (t % traceLen == 0) {
        val recordId = t / traceLen
        val sampleId = if (recordId < sampleNum) recordId else rnd.nextInt(recordId+1)
        if (sampleId < sampleNum) {
          lastSample match {
            case None =>
            case Some((sample, id)) => samples(id) = traces(sample)
          }
          val sample = Sample(readSnapshot)
          lastSample = Some((sample, sampleId)) 
          verifySnapshot(sample)
          traceCount = 0 
        }
      } 

      // take a step
      for (((in, id), i) <- inMap.zipWithIndex) {
        val data = pokeMap getOrElse (id, BigInt(0))
        pokePort(in, id, data)
        if (traceCount < traceLen) {
          inTraces(i) enqueue data
        }
      }
      peekMap.clear
      for (((out, id), i) <- outMap.zipWithIndex) {
        val data = peekPort(out, id)
        peekMap(id) = data 
        if (traceCount < traceLen) {
          outTraces(i) enqueue data
        } 
      }
      t += 1
      if (traceCount < traceLen) {
        traceCount += 1
      }
    }
  }

  protected[strober] def flush {
    // flush output tokens & traces from initialization
    peekMap.clear
    for ((out, id) <- outMap) {
      peekMap(id) = peekPort(out, id) 
    }
    for ((wire, i) <- outTrMap) {
      val trace = peekPort(wire, i)
    }
  }

  protected[strober] def init {
    for ((in, i) <- inMap.unzip._1.zipWithIndex) {
      assert(inTraces.size == i)
      inTraces += ScalaQueue[BigInt]()
    }
    for ((out, i) <- outMap.unzip._1.zipWithIndex) {
      assert(outTraces.size == i)
      outTraces += ScalaQueue[BigInt]()
    }
    flush
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
      file write (samples map (_.toString) mkString "")
    } finally {
      file.close
    }
    super.finish
  }
}

abstract class SimWrapperTester[+T <: SimWrapper[Module]](c: T, isTrace: Boolean = true) extends SimTester(c, isTrace) {
  protected[strober] val inMap = c.io.inMap
  protected[strober] val outMap = c.io.outMap
  protected[strober] val inTrMap = Map[Bits,Int]()
  protected[strober] val outTrMap = Map[Bits,Int]()
  protected[strober] def chunk(wire: Bits) = c.io.chunk(wire)

  protected[strober] def pokeChannel(addr: Int, data: BigInt) {
    while(_peek(c.io.ins(addr).ready) == 0) {
      takeStep
    }
    _poke(c.io.ins(addr).bits, data)
    _poke(c.io.ins(addr).valid, 1)
    takeStep
    _poke(c.io.ins(addr).valid, 0)
  }

  protected[strober] def peekChannel(addr: Int) = {
    while(_peek(c.io.outs(addr).valid) == 0) {
      takeStep
    }
    val value = _peek(c.io.outs(addr).bits)
    _poke(c.io.outs(addr).ready, 1)
    takeStep
    _poke(c.io.outs(addr).ready, 0)
    value
  }

  protected[strober] def readSnapshot = {
    val snap = new StringBuilder
    for (k <- 0 until sramMaxSize ; i <- 0 until sramSnapLen) {
      _poke(c.io.daisy.sram.restart, 1)
      while(_peek(c.io.daisy.sram.out.valid) == 0) {
        takeStep
      }
      _poke(c.io.daisy.sram.restart, 0)
      snap append intToBin(_peek(c.io.daisy.sram.out.bits), daisyWidth)
      _poke(c.io.daisy.sram.out.ready, 1)
      takeStep
      _poke(c.io.daisy.sram.out.ready, 0)
    }
    for (i <- 0 until traceSnapLen) {
      while(_peek(c.io.daisy.trace.out.valid) == 0) {
        takeStep
      }
      snap append intToBin(_peek(c.io.daisy.trace.out.bits), daisyWidth)
      _poke(c.io.daisy.trace.out.ready, 1)
      takeStep
      _poke(c.io.daisy.trace.out.ready, 0)
    }
    for (i <- 0 until regSnapLen) {
      while(_peek(c.io.daisy.regs.out.valid) == 0) {
        takeStep
      }
      snap append intToBin(_peek(c.io.daisy.regs.out.bits), daisyWidth)
      _poke(c.io.daisy.regs.out.ready, 1)
      takeStep
      _poke(c.io.daisy.regs.out.ready, 0)
    }
    snap.result
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

  init
}

abstract class NASTIShimTester[+T <: NASTIShim[SimNetwork]](c: T, isTrace: Boolean = true) extends SimTester(c, isTrace) {
  protected[strober] val inMap = c.master.inMap
  protected[strober] val outMap = c.master.outMap
  protected[strober] val inTrMap = c.master.inTrMap
  protected[strober] val outTrMap = c.master.outTrMap
  protected[strober] def chunk(wire: Bits) = c.sim.io.chunk(wire)

  protected[strober] def pokeChannel(addr: Int, data: BigInt) {
    do {
      _poke(c.io.mnasti.aw.bits.addr, addr << c.addrOffset)
      _poke(c.io.mnasti.aw.bits.id, 0)
      _poke(c.io.mnasti.aw.valid, 1)
      _poke(c.io.mnasti.w.bits.data, data)
      _poke(c.io.mnasti.w.valid, 1)
      takeStep
    } while (_peek(c.io.mnasti.aw.ready) == 0 || _peek(c.io.mnasti.w.ready) == 0)

    do {
      _poke(c.io.mnasti.aw.valid, 0)
      _poke(c.io.mnasti.w.valid, 0)
      takeStep
    } while(_peek(c.io.mnasti.b.valid) == 0)

    assert(_peek(c.io.mnasti.b.bits.id) == 0)
    _poke(c.io.mnasti.b.ready, 1)
  }

  protected[strober] def peekChannel(addr: Int) = {
    while (_peek(c.io.mnasti.ar.ready) == 0) {
      takeStep
    }

    _poke(c.io.mnasti.ar.bits.addr, addr << c.addrOffset)
    _poke(c.io.mnasti.ar.bits.id, 0)
    _poke(c.io.mnasti.ar.valid, 1)
    takeStep
    _poke(c.io.mnasti.ar.valid, 0)
 
    while (_peek(c.io.mnasti.r.valid) == 0) {
      takeStep
    }
      
    val data = _peek(c.io.mnasti.r.bits.data)
    assert(_peek(c.io.mnasti.r.bits.id) == 0)
    _poke(c.io.mnasti.r.ready, 1)
    takeStep
    _poke(c.io.mnasti.r.ready, 0)
    data
  }

  private val mem = Array.fill(1<<24){0.toByte} // size = 16MB

  def readMem(addr: BigInt) = {
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.addr), addr >> c.memBlockOffset)
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.tag), 0)
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.rw), 0)
    do {
      takeStep
    } while (_peek(c.io.snasti.ar.valid) == 0) 
    tickMem

    assert(peekChannel(c.master.respMap(c.mem.resp.bits.tag)) == 0)
    val id = c.master.respMap(c.mem.resp.bits.data)
    val data = peekPort(c.mem.resp.bits.data, id)
    if (isTrace) println("[MEM READ] addr: %x, data: %s".format(addr, data.toString(16)))
    data
  }

  def writeMem(addr: BigInt, data: BigInt) {
    if (isTrace) println("[MEM WRITE] addr: %x, data: %s".format(addr, data.toString(16)))
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.addr), addr >> c.memBlockOffset)
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.tag), 0)
    pokeChannel(c.master.reqMap(c.mem.req_cmd.bits.rw), 1)
    val id = c.master.reqMap(c.mem.req_data.bits.data)
    pokePort(c.mem.req_data.bits.data, id, data)
    do {
      takeStep
    } while (_peek(c.io.snasti.aw.valid) == 0)
    tickMem
  } 

  private def tickMem {
    if (_peek(c.io.snasti.ar.valid) == 1) {
      // handle read address
      val ar = _peek(c.io.snasti.ar.bits.addr).toInt & 0xffffff
      val tag = _peek(c.io.snasti.ar.bits.id)
      val len = _peek(c.io.snasti.ar.bits.len).toInt
      val size = 1 << _peek(c.io.snasti.ar.bits.size).toInt
      _poke(c.io.snasti.ar.ready, 1)
      do {
        takeStep
      } while (_peek(c.io.snasti.r.ready) == 0)
      _poke(c.io.snasti.ar.ready, 0)
      // handle read data
      for (k <- 0 to len) {
        var data = BigInt(0)
        for (i <- 0 until size) {
          val addr = ar + k*size + i
          data |= BigInt(mem(addr) & 0xff) << (8*i)
        }
        if (isTrace) println("[TICK MEM READ] addr: %x, data: %s".format(ar, data.toString(16)))
        _poke(c.io.snasti.r.bits.data, data)
        _poke(c.io.snasti.r.bits.id, tag)
        _poke(c.io.snasti.r.bits.last, 1)
        _poke(c.io.snasti.r.valid, 1)
        do {
          takeStep
        } while (_peek(c.io.snasti.r.ready) == 0)
        _poke(c.io.snasti.r.bits.last, 0)
        _poke(c.io.snasti.r.valid, 0)
      }
    } else if (_peek(c.io.snasti.aw.valid) == 1) {
      // handle write address
      val aw = _peek(c.io.snasti.ar.bits.addr).toInt & 0xffffff
      val len = _peek(c.io.snasti.aw.bits.len).toInt
      val size = 1 << _peek(c.io.snasti.aw.bits.size).toInt
      _poke(c.io.snasti.aw.ready, 1)
      takeStep
      _poke(c.io.snasti.aw.ready, 0)
      // handle write data
      for (k <- 0 to len) {
        while (_peek(c.io.snasti.w.valid) == 0) {
          takeStep
        }
        val data = _peek(c.io.snasti.w.bits.data)
        if (isTrace) println("[TICK MEM WRITE] addr: %x, data: %s".format(aw, data.toString(16)))
        for (i <- 0 until size) {
          val addr = aw + k*size + i
          mem(addr) = ((data >> (8*i)) & 0xff).toByte
        }
        _poke(c.io.snasti.w.ready, 1)
        assert(k < len || _peek(c.io.snasti.w.bits.last) == 1)
        takeStep
        _poke(c.io.snasti.w.ready, 0)
      }
    } else {
      _poke(c.io.snasti.ar.ready, 0)
      _poke(c.io.snasti.r.bits.last, 0)
      _poke(c.io.snasti.r.valid, 0)
      _poke(c.io.snasti.aw.ready, 0)
      _poke(c.io.snasti.w.ready, 0)
    }
  }

  private def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def loadMem(filename: String) {
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      for (k <- (line.length - 2) to 0 by -2) {
        val data = ((parseNibble(line(k)) << 4) | parseNibble(line(k+1))).toByte
        mem(base+offset) = data
        offset += 1
      }
    }
  }

  def slowLoadMem(filename: String) {
    println("[AXI4 LOADMEM] LOADING " + filename)
    val step = 1 << (c.memBlockOffset+1)
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      for (j <- (line.length - step) to 0 by -step) {
        var data = BigInt(0)
        for (k <- 0 until step) {
          data |= BigInt(parseNibble(line(j+k))) << (4*(step-1-k))
        }
        writeMem(base+offset, data)
        offset += step / 2
      }
    }
    println("[AXI4 LOADMEM] DONE ")
  }

  protected[strober] def readSnapshot = {
    val snap = new StringBuilder
    for (k <- 0 until sramMaxSize) {
      pokeChannel(c.master.sramRestartAddr, 0)
      for (i <- 0 until sramSnapLen) {
        snap append intToBin(peekChannel(c.master.snapOutMap(c.sim.io.daisy.sram.out)), daisyWidth)
      }
    }
    for (i <- 0 until traceSnapLen) {
      snap append intToBin(peekChannel(c.master.snapOutMap(c.sim.io.daisy.trace.out)), daisyWidth)
    }
    for (i <- 0 until regSnapLen) {
      snap append intToBin(peekChannel(c.master.snapOutMap(c.sim.io.daisy.regs.out)), daisyWidth)
    }
    snap.result
  }

  override def reset(n: Int) {
    // tail samples
    lastSample match {
      case None =>
      case Some((sample, id)) => samples(id) = traces(sample)
    }
    lastSample = None
    for (_ <- 0 until n) {
      pokeChannel(c.master.resetAddr, 0)
      super.reset(1)
    }
    flush
    t = 0
  }

  override def step(n: Int) {
    if (SimMemIO.size > 0) {
      for (i <- 0 until n) {
        super.step(1)
        (0 until 5) foreach (_ => takeStep)
        tickMem  
      }
    } else {
      super.step(n)
    }
  }

  for (_ <- 0 until 5) {
    pokeChannel(c.master.resetAddr, 0)
    super.reset(1)
  }
  init
}
