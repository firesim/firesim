package strober

import Chisel._
import scala.collection.mutable.{HashSet, HashMap}

class Replay[+T <: Module](c: T, samples: Seq[Sample], matchFile: Option[String], 
    testCmd: Option[String] = Driver.testCommand, dumpFile: Option[String] = None,
    logFile: Option[String] = None) extends Tester(c, false, testCmd, dumpFile) {
  private val matchMap = matchFile match {
    case None => Map[String, String]()
    case Some(f) => (scala.io.Source.fromFile(f).getLines map { line =>
      val tokens = line split " "
      tokens.head -> tokens.last }).toMap
  }
  case class SramInfo(cols: Int, dummy: Int, qwidth: Int)
  private val sramInfo = HashMap[Mem[_], SramInfo]()
  private val combRAMs = HashSet[Mem[_]]()
  private val addrRegs = HashMap[Reg, Mem[_]]()

  case class ReplayStartEvent(i: Int, cycle: Long) extends Event
  case class NoMatchEvent(path: String) extends Event
  case class ReportEvent(time: Double, speed: Double) extends Event
  class ReplayObserver(file: java.io.PrintStream) extends Observer(16, file) {
    override def apply(event: Event): Unit = event match {
      case ReplayStartEvent(i, cycle) =>
        file.println(s"START SAMPLE #${i}, cycle: ${cycle}")
      case NoMatchEvent(path) =>
        file.println(s"No match for ${path}")
      case ReportEvent(time, speed) =>
        val rpt = "Time elapsed = %.1f s, Simulation Speed = %.2f Hz".format(simTime, simSpeed)
        file.println(rpt)
        ChiselError.info(rpt)
      case _ => super.apply(event)
    }
  }

  addObserver(new ReplayObserver(logFile match { 
    case None    => System.out 
    case Some(f) => new java.io.PrintStream(f)
  }))

  // Sadly, not all seq mems are mapped to srams...
  // Query a node to the simulator
  // Unfortunatly, this can take very long with complex designs...
  Driver.dfs {
    case mem: Mem[_] if mem.seqRead && !mem.isInline =>
      if (peekPath(s"${dumpName(mem)}.sram.O1") != -1) {
        val cols = mem.dataType match {case v: Vec[_] => v.size case _ => 1}
        val width = 8 * ((mem.needWidth/cols-1) / 8 + 1)
        val dummy = (width*cols-mem.needWidth) / cols
        sramInfo(mem) = new SramInfo(cols, dummy, mem.needWidth/cols)
      } else {
        combRAMs += mem
        addrRegs(mem.readAccesses.head.asInstanceOf[MemSeqRead].addrReg) = mem
      }
    case _ =>
  }

  def loadWires(path: String, width: Int, value: BigInt, off: Option[Int]) {
    def loadff(path: String, v: BigInt) = (matchMap get path) match {
      case Some(p) => pokePath(p, v) case None => addEvent(new NoMatchEvent(path))
    }
    if (width == 1 && off == None) {
      loadff(path + (off map ("[" + _ + "]") getOrElse ""), value)
    } else (0 until width) foreach { idx =>
      loadff(path + (off map ("[" + _ + "]") getOrElse "") + "[" + idx + "]", (value >> idx) & 0x1)
    }
  }

  def loadWires(node: Node, value: BigInt, off: Option[Int]) {
    loadWires(dumpName(node), node.needWidth, value, off)
  } 

  // Replay samples
  val startTime = System.nanoTime
  samples.zipWithIndex foreach {case (sample, i) =>
    addEvent(new ReplayStartEvent(i, sample.cycle))
    reset(5)
    sample map {
      case Step(n) => step(n)
      case Force(node, value) => // Todo 
      case Load(node, value, off) => node match {
        case mem: Mem[_] if mem.seqRead && !mem.isInline && !combRAMs(mem) => off match {
          case None =>
            val info = sramInfo(mem)
            val u = UInt(value, mem.needWidth)
            val v = if (info.dummy == 0) value else
              Cat(((info.cols-1) to 0 by -1) map (i => 
              Cat(UInt(0, info.dummy), u((i+1)*info.qwidth-1, i*info.qwidth)))).litValue()
            pokePath(s"${dumpName(mem)}.sram.O1", v)
          case Some(p) if p < mem.n => 
            val info = sramInfo(mem)
            val u = UInt(value, mem.needWidth)
            val v = if (info.dummy == 0) value else 
              Cat(((info.cols) to 0 by -1) map (i => 
              Cat(UInt(0, info.dummy), u((i+1)*info.qwidth-1, i*info.qwidth)))).litValue()
            pokePath(s"${dumpName(mem)}.sram.memory[${p}]", v)
          case _ => // skip
        }
        case mem: Mem[_] if mem.seqRead && !mem.isInline => off match {
          case Some(p) if p < mem.n =>
            val path = s"${dumpName(mem)}.ram"
            if (matchMap.isEmpty) pokePath(s"${path}[${p}]", value) 
            else loadWires(path, mem.needWidth, value, off)
          case _ => // skip
        }
        case mem: Mem[_] if off == None => // skip
        case reg: Reg if addrRegs contains reg =>
          val mem = addrRegs(reg)
          val name = if (mem.readwrites.isEmpty) "reg_R1A" else "reg_RW0A"
          val path = s"${dumpName(mem)}.${name}"
          if (matchMap.isEmpty) pokePath(path, value) 
          else loadWires(path, node.needWidth, value, off)
        case _ => 
          if (matchMap.isEmpty) pokeNode(node, value, off) 
          else loadWires(node, value, off)
      }
      case PokePort(node, value) => poke(node, value)
      case ExpectPort(node, value) => expect(node, value)
    }
  }
  val endTime = System.nanoTime
  val simTime = (endTime - startTime) / 1000000000.0
  val simSpeed = t / simTime
  addEvent(new ReportEvent(simTime, simSpeed))
}
