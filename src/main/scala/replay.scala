package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import scala.io.Source

class Replay[+T <: Module](c: T, args: Seq[String] = Seq(), isTrace: Boolean = true) extends Tester(c, isTrace) {
  private val basedir = Driver.targetDir
  private val signalMap = HashMap[String, Node]()
  private val matchMap = HashMap[String, String]()
  private val samples = ArrayBuffer[Sample]()
  case class SramInfo(cols: Int, dummy: Int, qwidth: Int)
  private val sramInfo = HashMap[Mem[_], SramInfo]()
  private val notSRAMs = HashSet[Mem[_]]()
  private val addrRegs = HashMap[Reg, Mem[_]]()
  private var sampleFile: Option[String] = None

  def loadSamples(filename: String) {
    samples += (scala.io.Source.fromFile(filename).getLines foldLeft new Sample){(sample, line) =>
      val tokens = line split " "
      val cmd = SampleInstType(tokens.head.toInt)
      cmd match {
        case SampleInstType.CYCLE =>
          if (sample.cycle >= 0) samples += sample 
          new Sample(tokens.last.toLong)
        case SampleInstType.LOAD =>
          val value = BigInt(tokens.init.last, 16)
          val off = tokens.last.toInt
          (signalMap get tokens.tail.head) match {
            case None => println(s"${tokens.tail.head} not found")
            case Some(node) => sample addCmd Load(node, value, if (off < 0) None else Some(off))
          }
          sample
        case SampleInstType.FORCE => 
          val node = signalMap(tokens.tail.head)
          val value = BigInt(tokens.last, 16)
          sample addCmd Force(node, value)
          sample
        case SampleInstType.POKE => 
          val node = signalMap(tokens.tail.head) match {case b: Bits => b}
          val value = BigInt(tokens.last, 16)
          sample addCmd PokePort(node, value)
          sample
        case SampleInstType.STEP => 
          sample addCmd Step(tokens.last.toInt)
          sample
        case SampleInstType.EXPECT => 
          val node = signalMap(tokens.tail.head) match {case b: Bits => b}
          val value = BigInt(tokens.last, 16)
          sample addCmd ExpectPort(node, value)
          sample
        case SampleInstType.COUNT => sample // skip
      }
    }
  }

  def loadWires(path: String, width: Int, value: BigInt, off: Option[Int]) {
    def loadff(path: String, v: BigInt) = (matchMap get path) match {
      case Some(p) => pokePath(p, v) case None => println(s"No match for ${path}") // skip 
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

  def run {
    val startTime = System.nanoTime
    samples.zipWithIndex foreach {case (sample, i) =>
      println(s"START SAMPLE #${i}, cycle: ${sample.cycle}")
      reset(5)
      sample map {
        case Step(n) => step(n)
        case Force(node, value) => // Todo 
        case Load(node, value, off) => node match {
          case mem: Mem[_] if mem.seqRead && !mem.isInline && !notSRAMs(mem) => off match {
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
    ChiselError.info("Time elapsed = %.1f s, Simulation Speed = %.2f Hz".format(simTime, simSpeed))
  }

  args foreach { arg =>
    if (arg.slice(0, 7) == "+match=") {
      val lines = scala.io.Source.fromFile(arg.substring(7)).getLines
      lines foreach { line =>
        val tokens = line split " "
        matchMap(tokens.head) = tokens.last
      }
    }
    if (arg.slice(0, 8) == "+sample=") {
      sampleFile = Some(arg.substring(8))
    }
  }

  Driver.dfs {
    case node: Delay       => signalMap(dumpName(node)) = node
    case node if node.isIo => signalMap(dumpName(node)) = node
    case _ =>
  }
  Driver.dfs {
    case mem: Mem[_] if mem.seqRead && !mem.isInline =>
      if (peekPath(s"${dumpName(mem)}.sram.O1") != -1) {
        val cols = mem.dataType match {case v: Vec[_] => v.size case _ => 1}
        val width = 8 * ((mem.needWidth/cols-1) / 8 + 1)
        val dummy = (width*cols-mem.needWidth) / cols
        sramInfo(mem) = new SramInfo(cols, dummy, mem.needWidth/cols)
      } else {
        notSRAMs += mem
        addrRegs(mem.readAccesses.head match {
          case msr: MemSeqRead => msr.addrReg
        }) = mem
      }
    case _ =>
  }
  loadSamples(sampleFile match {case None => basedir + c.name + ".sample" case Some(f) => f})
  run
}
