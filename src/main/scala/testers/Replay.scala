package strober
package testers

import chisel3.Module
import chisel3.iotesters.PeekPokeTester
import java.io.File

abstract class Replay[+T <: Module](
    c: T,
    sampleFile: File,
    logFile: Option[File]) extends PeekPokeTester(c, true, 16, logFile)

class RTLReplay[+T <: Module](
    c: T,
    sampleFile: File = ReplayCompiler.context.sample,
    logFile: Option[File] = None) extends Replay(c, sampleFile, logFile) {
  private val samples = Sample(sampleFile)
  // Replay samples
  val startTime = System.nanoTime
  val expects = collection.mutable.ArrayBuffer[ExpectPort]()
  samples.zipWithIndex foreach {case (sample, i) =>
    println(s"cycle: ${sample.cycle}")
    reset(5)
    (sample fold (true, false)){
      case ((first, _), Step(n)) =>
        step(n)
        (first, false)
      case ((first, _), Force(node, value)) =>
        backend.poke(node, value)
        (first, false)
      case ((first, _), Load(node, value, off)) =>
        val path = if (off < 0) node else s"$node[$off]"
        backend.poke(path, value)
        (first, false)
      case ((first, expected), PokePort(node, value)) =>
        if (expected) step(1)
        backend.poke(node, value)
        (first && !expected, false)
      case ((first, _), ExpectPort(node, value)) =>
        if (!first && !backend.expect(node, value)) fail
        (first, true)
      case ((first, expected), Count(_, _)) =>
        (first, expected)
    }
  }
  val endTime = System.nanoTime
  val simTime = (endTime - startTime) / 1000000000.0
  val simSpeed = t / simTime
  println("Time elapsed = %.1f s, Simulation Speed = %.2f Hz".format(simTime, simSpeed))
}

/*** Gate-level simulation has different timing models ***/
class GateLevelReplay[+T <: Module](
    c: T,
    sampleFile: File = ReplayCompiler.context.sample,
    // matchFile: File,
    logFile: Option[File] = None) extends Replay(c, sampleFile, logFile) {
  private val samples = Sample(sampleFile)
  // private val matchMap = ((scala.io.Source fromFile matchFile).getLines map {
  //  line => val tokens = line split " " ; tokens.head -> tokens.last }).toMap

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
    println(s"cycle: ${sample.cycle}")
    reset(5)
    (sample fold (true, false, false)) {
      case ((first, _, _), Step(n)) =>
        step(n)
        (first, false, false)
      case ((first, _, _), Force(node, value)) =>
        backend.poke(node, value)
        (first, false, false)
      case ((first, _, _), Load(node, value, off)) =>
        val path = if (off < 0) node else s"$node[$off]"
        backend.poke(path, value)
        (first, false, false)
      case ((first, _, expected), PokePort(node, value)) =>
        backend.poke(node, value)
        (first && !expected, true, false)
      case ((first, poked, _), ExpectPort(node, value)) =>
        if (poked) step(1)
        if (!first && !backend.expect(node, value)) fail
        (first, false, true)
      case ((first, poked, expected), Count(_, _)) =>
        (first, poked, expected)
    }
    step(1)
  }
  val endTime = System.nanoTime
  val simTime = (endTime - startTime) / 1000000000.0
  val simSpeed = t / simTime
  println("Time elapsed = %.1f s, Simulation Speed = %.2f Hz".format(simTime, simSpeed))
}
