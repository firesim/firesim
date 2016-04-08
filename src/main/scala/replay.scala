package strober

import Chisel._
import scala.collection.mutable.{HashSet, HashMap}

case class ReplayArgs(
  samples: Seq[Sample], 
  dumpFile: Option[String] = None,
  logFile: Option[String] = None,
  matchFile: Option[String] = None, 
  testCmd: Option[String] = Driver.testCommand, 
  verbose: Boolean = true
)
class Replay[+T <: Module](c: T, args: ReplayArgs) 
    extends Tester(c, false, 16, args.testCmd, args.dumpFile) {
  private val log = args.logFile match { 
    case None    => System.out 
    case Some(f) => new java.io.PrintStream(f)
  }
  private val matchMap = args.matchFile match {
    case None => Map[String, String]()
    case Some(f) => (scala.io.Source.fromFile(f).getLines map { line =>
      val tokens = line split " "
      tokens.head -> tokens.last }).toMap
  }
  case class SramInfo(path: String, cols: Int, dummy: Int, qwidth: Int)
  private val sramInfo = HashMap[Mem[_], SramInfo]()
  private val combRAMs = HashSet[Mem[_]]()
  private val addrRegs = HashMap[Reg, Mem[_]]()

  private val noMatches = HashSet[String]()
  case class ReplayStartEvent(i: Int, cycle: Long) extends Event
  case class NoMatchEvent(path: String) extends Event
  case class ForceEvent(node: Node, value: BigInt) extends Event
  class ReplayObserver(file: java.io.PrintStream) extends Observer(16, file) {
    override def apply(event: Event): Unit = event match {
      case ReplayStartEvent(i, cycle) =>
        file.println(s"START SAMPLE #${i}, cycle: ${cycle}")
      case NoMatchEvent(path) if !noMatches(path) =>
        noMatches += path
        file.println(s"No match for ${path}")
      case ForceEvent(node, value) =>
        file.println(s"  FORCE ${dumpName(node)} <- ${value}")
      case _ => super.apply(event)
    }
  }

  if (args.verbose) addObserver(new ReplayObserver(log))

  // Sadly, not all seq mems are mapped to srams...
  private def addSramInfo(mem: Mem[_]) {
    val path = matchMap getOrElse (s"${dumpName(mem)}.sram", s"${dumpName(mem)}.sram")
    val cols = mem.dataType match {case v: Vec[_] => v.size case _ => 1}
    val width = 8 * ((mem.needWidth/cols-1) / 8 + 1)
    val dummy = (width*cols-mem.needWidth) / cols
    sramInfo(mem) = new SramInfo(path, cols, dummy, mem.needWidth/cols)
  }
  private def addCombRAM(mem: Mem[_]) {
    combRAMs += mem
    addrRegs(mem.readAccesses.head.asInstanceOf[MemSeqRead].addrReg) = mem
  }
  Driver.dfs {
    // Find sram blocks if the match file is available
    case mem: Mem[_] if mem.seqRead && !mem.isInline && !matchMap.isEmpty =>
      if (matchMap contains dumpName(mem))
      addSramInfo(mem) else addCombRAM(mem)
    // Otherwise, query a node to the simulator
    // This can take very long...
    case mem: Mem[_] if mem.seqRead && !mem.isInline =>
      if (peekPath(s"${dumpName(mem)}.sram.O1") != -1) 
      addSramInfo(mem) else addCombRAM(mem)
    case _ =>
  }

  def loadWires(path: String, width: Int, value: BigInt, off: Option[Int], force: Boolean) {
    def loadbit(path: String, v: BigInt, force: Boolean) = (matchMap get path) match {
      case Some(p) => pokePath(p, v, force) case None => addEvent(new NoMatchEvent(path))
    }
    if (width == 1 && off == None) {
      loadbit(s"""${path}${(off map (x => s"[${x}]") getOrElse "")}""", value, force)
    } else (0 until width) foreach { idx =>
      loadbit(s"""${path}${(off map (x => s"[${x}]") getOrElse "")}[${idx}]""", (value >> idx) & 0x1, force)
    }
  }

  def loadWires(node: Node, value: BigInt, off: Option[Int], force: Boolean) {
    loadWires(dumpName(node), node.needWidth, value, off, force)
  } 

  // Replay samples
  val startTime = System.nanoTime
  args.samples.zipWithIndex foreach {case (sample, i) =>
    addEvent(new ReplayStartEvent(i, sample.cycle))
    reset(5)
    (sample fold false){
      case (f, Step(n)) =>
        if (f) dumpoff
        step(n)
        if (f) dumpon
        false
      case (f, Force(node, value)) =>
        addEvent(new ForceEvent(node, value))
        if (matchMap.isEmpty) pokeNode(node, value, force=true)
        else loadWires(node, value, None, true)
        true
      case (f, Load(node, value, off)) =>
        assert(!f)
        node match {
          case mem: Mem[_] if mem.seqRead && !mem.isInline && !combRAMs(mem) => off match {
            case None =>
              val info = sramInfo(mem)
              val u = UInt(value, mem.needWidth)
              val v = if (info.dummy == 0) value else
                Cat(((info.cols-1) to 0 by -1) map (i => 
                Cat(UInt(0, info.dummy), u((i+1)*info.qwidth-1, i*info.qwidth)))).litValue()
              pokePath(s"${info.path}.O1", v)
            case Some(p) if p < mem.n => 
              val info = sramInfo(mem)
              val u = UInt(value, mem.needWidth)
              val v = if (info.dummy == 0) value else 
                Cat(((info.cols) to 0 by -1) map (i => 
                Cat(UInt(0, info.dummy), u((i+1)*info.qwidth-1, i*info.qwidth)))).litValue()
              pokePath(s"${info.path}.memory[${p}]", v)
            case _ => // skip
          }
          case mem: Mem[_] if mem.seqRead && !mem.isInline => off match {
            case Some(p) if p < mem.n =>
              val path = s"${dumpName(mem)}.ram"
              if (matchMap.isEmpty) pokePath(s"${path}[${p}]", value) 
              else loadWires(path, mem.needWidth, value, off, false)
            case _ => // skip
          }
          case mem: Mem[_] if off == None => // skip
          case reg: Reg if addrRegs contains reg =>
            val mem = addrRegs(reg)
            val name = if (mem.readwrites.isEmpty) "reg_R1A" else "reg_RW0A"
            val path = s"${dumpName(mem)}.${name}"
            if (matchMap.isEmpty) pokePath(path, value) 
            else loadWires(path, node.needWidth, value, off, false)
          case _ => 
            if (matchMap.isEmpty) pokeNode(node, value, off, false) 
            else loadWires(node, value, off, false)
        }
        false
      case (f, PokePort(node, value)) =>
        assert(!f)
        poke(node, value)
        false
      case (f, ExpectPort(node, value)) =>
        assert(!f)
        expect(node, value)
        false
    }
  }
  val endTime = System.nanoTime
  val simTime = (endTime - startTime) / 1000000000.0
  val simSpeed = t / simTime
  log.println("Time elapsed = %.1f s, Simulation Speed = %.2f Hz".format(simTime, simSpeed))
}
