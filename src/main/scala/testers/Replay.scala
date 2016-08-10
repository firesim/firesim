package strober
package testers

import chisel3.Module
import chisel3.iotesters.PeekPokeTester

class Replay[+T <: Module](c: T, args: Array[String], logFile: Option[String] = None,
    wavefrom: Option[String] = None, testCmd: List[String] = Nil)
    extends PeekPokeTester(c, true, 16, logFile, wavefrom, testCmd, false) {
  private sealed trait ReplayOption
  private case object SampleFile extends ReplayOption
  private case object MatchFile extends ReplayOption
  private type OptionMap = Map[ReplayOption, String]
  private def parseOption(map: OptionMap, args: List[String]): OptionMap = args match {
    case Nil => map
    case "--sample" :: value :: tail =>
      parseOption(map + (SampleFile -> value), tail)
    case "--match" :: value :: tail =>
      parseOption(map + (MatchFile -> value), tail)
    case option :: tail => throw new Exception(s"Unknown option: $option")
  }
  private val options = parseOption(Map(), args.toList)
  private val samples = options get SampleFile match {
    case Some(f) => Sample(f)
    case None => throw new Exception(s"Sample file should be provided with '--sample'")
  }

  private val matchMap = options get MatchFile match {
    case None => Map[String, String]()
    case Some(f) => (scala.io.Source.fromFile(f).getLines map { line =>
      val tokens = line split " "
      tokens.head -> tokens.last }).toMap
  }

  /* TODO:
  case class SramInfo(path: String, cols: Int, dummy: Int, qwidth: Int)
  private val sramInfo = HashMap[Mem[_], SramInfo]()
  private val combRAMs = HashSet[Mem[_]]()
  private val addrRegs = HashMap[Reg, Mem[_]]()
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
  def loadWires(path: String, width: Int, value: BigInt, off: Option[Int], force: Boolean) {
    def loadbit(path: String, v: BigInt, force: Boolean) = (matchMap get path) match {
      case Some(p) => pokePath(p, v, force) case None =>
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
  */

  // Replay samples
  val startTime = System.nanoTime
  samples.zipWithIndex foreach {case (sample, i) =>
    reset(5)
    (sample fold 0){
      case (cycles, Step(n)) =>
        step(n)
        cycles + 1
      case (cycles, Force(node, value)) =>
        backend.poke(node, value)
        cycles
      case (cycles, Load(node, value, off)) =>
        backend.poke(node, value)
        cycles
      case (cycles, PokePort(node, value)) =>
        backend.poke(node, value)
        cycles
      case (cycles, ExpectPort(node, expected)) =>
        if (cycles > 1 && !backend.expect(node, expected)) fail
        cycles
    }
  }
  val endTime = System.nanoTime
  val simTime = (endTime - startTime) / 1000000000.0
  val simSpeed = t / simTime
  println("Time elapsed = %.1f s, Simulation Speed = %.2f Hz".format(simTime, simSpeed))
}
