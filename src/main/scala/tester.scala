package strober

import Chisel._
import scala.collection.mutable.{HashMap, Queue => ScalaQueue}
import scala.io.Source

abstract class SimTester[+T <: Module](c: T, isTrace: Boolean) extends Tester(c, false) {
  protected val inMap = transforms.inMap
  protected val outMap = transforms.outMap
  private val pokeMap = HashMap[Int, BigInt]()
  private val peekMap = HashMap[Int, BigInt]()
  protected def traceLen: Int
  private var traceCount = 0
  private val inTraces = HashMap[Int, ScalaQueue[BigInt]]()
  private val outTraces = HashMap[Int, ScalaQueue[BigInt]]()
  protected val inTraceMap = transforms.inTraceMap
  protected val outTraceMap = transforms.outTraceMap
  protected def daisyWidth: Int
  protected lazy val regSnapLen = transforms.regSnapLen
  protected lazy val sramSnapLen = transforms.sramSnapLen
  protected lazy val sramMaxSize = transforms.sramMaxSize
  protected def sampleNum: Int
  private lazy val samples = Array.fill(sampleNum){new Sample}
  private var lastSample: Option[(Sample, Int)] = None

  protected def pokeChannel(addr: Int, data: BigInt): Unit
  protected def peekChannel(addr: Int): BigInt

  protected def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def pokePort(port: Bits, x: BigInt) {
    assert(inMap contains port)
    if (isTrace) println("* POKE " + dumpName(port) + " <- " + x.toString(16) + " *")
    pokeMap(inMap(port)) = x 
  }
 
  def peekPort(port: Bits) = {
    assert(outMap contains port)
    assert(peekMap contains outMap(port))
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
 
  def expectPort(port: Bits, expected: BigInt) = {
    assert(outMap contains port)
    assert(peekMap contains outMap(port))
    val value = peekMap(outMap(port))
    val pass = value == expected 
    expect(pass, "* EXPECT " + dumpName(port) + " -> " + value.toString(16) + " == " + expected.toString(16))
  }

  def tracePorts(sample: Sample) = {
    for (i <- 0 until traceLen) {
      for ((wire, i) <- inMap) {
        val trace = inTraces(i)
        assert(i != 0 || trace.size == traceLen, 
          "trace size: %d != trace len: %d".format(trace.size, traceLen))
        sample addCmd PokePort(wire, trace.dequeue)
      }
      for ((wire, i) <- inTraceMap) {
        sample addCmd PokePort(wire, peekChannel(i))
      }
      sample addCmd Step(1)
      for ((wire, i) <- outMap) {
        val trace = outTraces(i)
        assert(i != 0 || trace.size == traceLen,
          "trace size: %d != trace len: %d".format(trace.size, traceLen))
        sample addCmd ExpectPort(wire, trace.dequeue)
      }
      for ((wire, i) <- outTraceMap) {
        sample addCmd ExpectPort(wire, peekChannel(i))
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

  def readSnapshot: String

  def verifySnapshot(sample: Sample) = {
    val pass = (sample map {
      case Load(signal, value, off) => 
        peekBits(signal, off.getOrElse(-1)) == value
      case _ => true   
    }) reduce (_ && _)
    expect(pass, "* SNAPSHOT")
  }

  override def step(n: Int) {
    if (isTrace) println("STEP " + n + " -> " + (t + n))
    for (i <- 0 until n) {
      for ((in, id) <- inMap) {
        val data = pokeMap getOrElse (id, BigInt(0))
        pokeChannel(id, data)
        if (traceCount < traceLen) {
          inTraces(id) enqueue data
        }
      }
      peekMap.clear
      for ((out, id) <- outMap) {
        val data = peekChannel(id)
        peekMap(id) = data
        if (traceCount < traceLen) {
          outTraces(id) enqueue data
        }
      }

      if (traceCount < traceLen) {
        traceCount += 1
      }
      // reservoir sampling
      if (t % traceLen == 0) {
        val recordId = t / traceLen
        val sampleId = if (recordId < sampleNum) recordId else rnd.nextInt(recordId+1)
        if (sampleId < sampleNum) {
          lastSample match {
            case None =>
            case Some((sample, id)) => samples(id) = tracePorts(sample)
          }
          val sample = Sample(readSnapshot)
          lastSample = Some((sample, sampleId))
          verifySnapshot(sample)
          traceCount = 0
        }
      } 
      t += 1
    }
  }

  def init {
    traceCount = traceLen
    // Consumes initial output tokens
    peekMap.clear
    for ((in, id) <- inMap) {
      inTraces(id) = ScalaQueue[BigInt]()
    }
    for ((out, id) <- outMap) {
      peekMap(id) = peekChannel(id)
      outTraces(id) = ScalaQueue[BigInt]()
    }
  }

  override def finish = {
    val res = new StringBuilder
    for (sample <- samples) {
      res append sample.toString
    }
    val filename = transforms.targetName + ".sample"
    val file = createOutputFile(filename)
    try {
      file write res.result
    } finally {
      file.close
    }
    super.finish
  }
}

abstract class SimWrapperTester[+T <: SimWrapper[Module]](c: T, isTrace: Boolean = true) 
  extends SimTester(c, isTrace) {
  protected val sampleNum = c.sampleNum
  protected val traceLen = c.traceLen
  protected val daisyWidth = c.daisyWidth

  def pokeChannel(addr: Int, data: BigInt) {
    while(peek(c.io.ins(addr).ready) == 0) {
      takeSteps(1)
    }
    poke(c.io.ins(addr).bits.data, data)
    poke(c.io.ins(addr).valid, 1)
    takeSteps(1)
    poke(c.io.ins(addr).valid, 0)
  }

  def peekChannel(addr: Int) = {
    while(peek(c.io.outs(addr).valid) == 0) {
      takeSteps(1)
    }
    val value = peek(c.io.outs(addr).bits.data)
    poke(c.io.outs(addr).ready, 1)
    takeSteps(1)
    poke(c.io.outs(addr).ready, 0)
    value
  }

  def readSnapshot = {
    val snap = new StringBuilder
    for (i <- 0 until regSnapLen) {
      while(peek(c.io.daisy.regs.out.valid) == 0) {
        takeSteps(1)
      }
      snap append intToBin(peek(c.io.daisy.regs.out.bits), daisyWidth)
      poke(c.io.daisy.regs.out.ready, 1)
      takeSteps(1)
      poke(c.io.daisy.regs.out.ready, 0)
    }
    for (k <- 0 until sramMaxSize ; i <- 0 until sramSnapLen) {
      poke(c.io.daisy.sram.restart, 1)
      while(peek(c.io.daisy.sram.out.valid) == 0) {
        takeSteps(1)
      }
      poke(c.io.daisy.sram.restart, 0)
      snap append intToBin(peek(c.io.daisy.sram.out.bits), daisyWidth)
      poke(c.io.daisy.sram.out.ready, 1)
      takeSteps(1)
      poke(c.io.daisy.sram.out.ready, 0)
    }
    snap.result
  }

  init
}

abstract class SimAXI4WrapperTester[+T <: SimAXI4Wrapper[SimNetwork]](c: T, isTrace: Boolean = true) 
  extends SimTester(c, isTrace) {
  protected val sampleNum = c.sim.sampleNum
  protected val traceLen = c.sim.traceLen
  protected val daisyWidth = c.sim.daisyWidth
  private val reqMap = transforms.reqMap
  private val respMap = transforms.respMap
  private val snapOutMap = transforms.snapOutMap
  private lazy val inWidths = (inMap ++ reqMap) map {case (k, v) => v -> k.needWidth}
  private lazy val outWidths = 
    (outMap ++ respMap ++ inTraceMap ++ outTraceMap ++ snapOutMap) map {case (k, v) => v -> k.needWidth}

  def pokeChannel(addr: Int, data: BigInt) {
    val mask = (BigInt(1) << c.m_axiDataWidth) - 1
    val limit = if (addr == c.resetAddr) 1 else (inWidths(addr) - 1) / c.m_axiDataWidth + 1
    for (i <- limit - 1 to 0 by -1) {
      val maskedData = (data >> (i * c.m_axiDataWidth)) & mask
      do {
        poke(c.io.M_AXI.aw.bits.id, 0)
        poke(c.io.M_AXI.aw.bits.addr, addr << c.addrOffset)
        poke(c.io.M_AXI.aw.valid, 1)
        poke(c.io.M_AXI.w.bits.data, maskedData)
        poke(c.io.M_AXI.w.valid, 1)
        takeSteps(1)
      } while (peek(c.io.M_AXI.aw.ready) == 0 || peek(c.io.M_AXI.w.ready) == 0)

      do {
        poke(c.io.M_AXI.aw.valid, 0)
        poke(c.io.M_AXI.w.valid, 0)
        takeSteps(1)
      } while (peek(c.io.M_AXI.b.valid) == 0)

      assert(peek(c.io.M_AXI.b.bits.id) == 0)
      poke(c.io.M_AXI.b.ready, 1)
      takeSteps(1)
      poke(c.io.M_AXI.b.ready, 0)
    }
  }

  def peekChannel(addr: Int) = {
    var data = BigInt(0)
    val limit = (outWidths(addr) - 1) / c.m_axiDataWidth + 1
    for (i <- 0 until limit) {
      while (peek(c.io.M_AXI.ar.ready) == 0) {
        takeSteps(1)
      }

      poke(c.io.M_AXI.ar.bits.addr, addr << c.addrOffset)
      poke(c.io.M_AXI.ar.bits.id, 0)
      poke(c.io.M_AXI.ar.valid, 1)
      takeSteps(1)
      poke(c.io.M_AXI.ar.valid, 0)

      while (peek(c.io.M_AXI.r.valid) == 0) {
        takeSteps(1)
      }

      data |= peek(c.io.M_AXI.r.bits.data) << (i * c.m_axiDataWidth)
      assert(peek(c.io.M_AXI.r.bits.id) == 0)
      poke(c.io.M_AXI.r.ready, 1)
      takeSteps(1)
      poke(c.io.M_AXI.r.ready, 0)
    }
    assert(peek(c.io.M_AXI.r.valid) == 0)
    data
  }

  private val mem = Array.fill(1<<23){0.toByte} // size = 8MB

  def readMem(addr: BigInt) = {
    pokeChannel(reqMap(c.memReq.addr), addr >> c.blockOffset)
    pokeChannel(reqMap(c.memReq.tag), 0)
    do {
      takeSteps(1)
    } while (peek(c.io.S_AXI.ar.valid) == 0) 
    tickMem

    var data = BigInt(0)
    for (i <- 0 until c.memDataCount) {
      assert(peekChannel(respMap(c.memResp.tag)) == 0)
      data |= peekChannel(respMap(c.memResp.data)) << (i * c.memDataWidth)
    }
    data
  }

  def writeMem(addr: BigInt, data: BigInt) {
    pokeChannel(reqMap(c.memReq.addr), addr >> c.blockOffset)
    pokeChannel(reqMap(c.memReq.tag), 1)
    for (i <- 0 until c.memDataCount) {
      pokeChannel(reqMap(c.memReq.data), data >> (i * c.memDataWidth))
    }
    do {
      takeSteps(1)
    } while (peek(c.io.S_AXI.aw.valid) == 0) 
    tickMem
  } 

  private def tickMem {
    if (peek(c.io.S_AXI.ar.valid) == 1) {
      // handle read address
      val ar = peek(c.io.S_AXI.ar.bits.addr).toInt & 0xffff
      val tag = peek(c.io.S_AXI.ar.bits.id)
      val len = peek(c.io.S_AXI.ar.bits.len).toInt
      val size = 1 << peek(c.io.S_AXI.ar.bits.size).toInt
      poke(c.io.S_AXI.ar.ready, 1)
      do {
        takeSteps(1)
      } while (peek(c.io.S_AXI.r.ready) == 0)
      poke(c.io.S_AXI.ar.ready, 0)
      // handle read data
      for (k <- 0 to len) {
        var data = BigInt(0)
        for (i <- 0 until size) {
          val addr = ar+k*(size+1)+i
          data |= BigInt(mem(addr) & 0xff) << (8*i)
        }
        poke(c.io.S_AXI.r.bits.data, data)
        poke(c.io.S_AXI.r.bits.id, tag)
        poke(c.io.S_AXI.r.bits.last, 1)
        poke(c.io.S_AXI.r.valid, 1)
        do {
          takeSteps(1)
        } while (peek(c.io.S_AXI.r.ready) == 0)
        poke(c.io.S_AXI.r.bits.last, 0)
        poke(c.io.S_AXI.r.valid, 0)
      }
    } else if (peek(c.io.S_AXI.aw.valid) == 1) {
      // handle write address
      val aw = peek(c.io.S_AXI.ar.bits.addr).toInt & 0xffff
      val len = peek(c.io.S_AXI.aw.bits.len).toInt
      val size = 1 << peek(c.io.S_AXI.aw.bits.size).toInt
      poke(c.io.S_AXI.aw.ready, 1)
      do {
        takeSteps(1)
      } while (peek(c.io.S_AXI.w.valid) == 0)
      poke(c.io.S_AXI.aw.ready, 0)
      // handle write data
      for (k <- 0 to len) {
        while (peek(c.io.S_AXI.w.valid) == 0) {
          takeSteps(1)
        }
        val data = peek(c.io.S_AXI.w.bits.data)
        for (i <- 0 until size) {
          val addr = aw+k*(size+1)+i
          mem(addr) = ((data >> (8*i)) & 0xff).toByte
        }
        poke(c.io.S_AXI.w.ready, 1)
        assert(k < len || peek(c.io.S_AXI.w.bits.last) == 1)
        takeSteps(1)
        poke(c.io.S_AXI.w.ready, 0)
      }
    } else {
      poke(c.io.S_AXI.ar.ready, 0)
      poke(c.io.S_AXI.r.bits.last, 0)
      poke(c.io.S_AXI.r.valid, 0)
      poke(c.io.S_AXI.aw.ready, 0)
      poke(c.io.S_AXI.w.ready, 0)
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
    val step = 1 << (c.blockOffset+1)
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      for (j <- (line.length - step) to 0 by -step) {
        var data = BigInt(0)
        for (k <- 0 until step) {
          data |= parseNibble(line(j+k)) << (4*(step-1-k))
        }
        writeMem(base+offset, data)
        offset += step / 2
      }
    }
  }

  def readSnapshot = {
    val snap = new StringBuilder
    for (i <- 0 until regSnapLen) {
      snap append intToBin(peekChannel(snapOutMap(c.snapOut.regs)), daisyWidth)
    }
    for (k <- 0 until sramMaxSize ; i <- 0 until sramSnapLen) {
      snap append intToBin(peekChannel(snapOutMap(c.snapOut.sram)), daisyWidth)
    }
    snap.result
  }

  override def step(n: Int) {
    if (MemIO.count > 0) {
      for (i <- 0 until n) {
        super.step(1)
        tickMem  
      }
    } else {
      super.step(n)
    }
  }

  pokeChannel(c.resetAddr, 0)
  init
}
