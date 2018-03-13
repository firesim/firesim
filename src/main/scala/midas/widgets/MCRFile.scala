// See LICENSE for license details.

package midas
package widgets

import junctions._

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}
import CppGenerationUtils._

case class Permissions(readable: Boolean, writeable: Boolean)
object ReadOnly extends Permissions(true, false)
object WriteOnly extends Permissions(false, true)
object ReadWrite extends Permissions(true, true)

abstract class MCRMapEntry {
  def name: String
  def permissions: Permissions
}

case class DecoupledSinkEntry(node: DecoupledIO[UInt], name: String) extends MCRMapEntry {
  val permissions = WriteOnly
}
case class DecoupledSourceEntry(node: DecoupledIO[UInt], name: String) extends MCRMapEntry {
  val permissions = ReadOnly
}
case class RegisterEntry(node: Data, name: String, permissions: Permissions) extends MCRMapEntry

class MCRFileMap() {
  private val name2addr = LinkedHashMap[String, Int]()
  private val regList = ArrayBuffer[MCRMapEntry]()

  def allocate(entry: MCRMapEntry): Int = {
    Predef.assert(!name2addr.contains(entry.name), "name already allocated")
    val address = name2addr.size
    name2addr += (entry.name -> address)
    regList.append(entry)
    address
  }

  def lookupAddress(name: String): Option[Int] = name2addr.get(name)

  def numRegs: Int = regList.size

  def bindRegs(mcrIO: MCRIO): Unit = regList.zipWithIndex foreach {
    case (e: DecoupledSinkEntry, addr) => mcrIO.bindDecoupledSink(e, addr)
    case (e: DecoupledSourceEntry, addr) => mcrIO.bindDecoupledSource(e, addr)
    case (e: RegisterEntry, addr) => mcrIO.bindReg(e, addr)
  }

  def genHeader(prefix: String, base: BigInt, sb: StringBuilder): Unit = {
    name2addr.toList foreach { case (regName, idx) =>
      val fullName = s"${prefix}_${regName}"
      val address = base + idx
      sb append s"#define ${fullName} ${address}\n"
    }
  }
  // A variation of above which dumps the register map as a series of arrays
  def genArrayHeader(prefix: String, base: BigInt, sb: StringBuilder) {
    def emitArrays(regs: Seq[(MCRMapEntry, BigInt)], prefix: String) {
      sb.append(genConstStatic(s"${prefix}_num_registers", UInt32(regs.size)))
      sb.append(genArray(s"${prefix}_names", regs.unzip._1 map { reg => CStrLit(reg.name)}))
      sb.append(genArray(s"${prefix}_addrs", regs.unzip._2 map { addr => UInt32(addr)}))
    }

    val regAddrs = regList map (reg => reg -> (base + lookupAddress(reg.name).get))
    val readRegs = regAddrs filter (_._1.permissions.readable)
    val writeRegs = regAddrs filter (_._1.permissions.writeable)
    emitArrays(readRegs, prefix + "_R")
    emitArrays(writeRegs, prefix + "_W")
  }

  // Returns a copy of the current register map
  def getRegMap = name2addr.toMap

  def printCRs {
    regList.zipWithIndex foreach { case (entry, i) => println(s"Name: ${entry.name}, Addr: $i") }
  }
}

class MCRIO(numCRs: Int)(implicit p: Parameters) extends NastiBundle()(p) {
  val read = Vec(numCRs, Flipped(Decoupled(UInt(nastiXDataBits.W))))
  val write = Vec(numCRs, Decoupled(UInt(nastiXDataBits.W)))
  val wstrb = Output(UInt(nastiWStrobeBits.W))

  def bindReg(reg: RegisterEntry, addr: Int): Unit = {
    if (reg.permissions.writeable) {
      when(write(addr).valid){
        reg.node := write(addr).bits
      }
    } else {
      assert(write(addr).valid != true.B, s"Register ${reg.name} is read only")
    }

    if (reg.permissions.readable) {
      read(addr).bits := reg.node
    } else {
      assert(read(addr).ready === false.B, "Register ${reg.name} is write only")
    }

    read(addr).valid := true.B
    write(addr).ready := true.B
  }

  def bindDecoupledSink(channel: DecoupledSinkEntry, addr: Int): Unit = {
    channel.node <> write(addr)
    assert(read(addr).ready === false.B, "Can only write to this decoupled sink")
  }

  def bindDecoupledSource(channel: DecoupledSourceEntry, addr: Int): Unit = {
    read(addr) <> channel.node
    assert(write(addr).valid =/= true.B, "Can only read from this decoupled source")
  }

}

class MCRFile(numRegs: Int)(implicit p: Parameters) extends NastiModule()(p) {
  val io = IO(new Bundle {
    val nasti = Flipped(new NastiIO)
    val mcr = new MCRIO(numRegs)
  })

  //TODO: Just use a damn state machine.
  val rValid = RegInit(false.B)
  val arFired = RegInit(false.B)
  val awFired = RegInit(false.B)
  val wFired = RegInit(false.B)
  val wCommited = RegInit(false.B)
  val bId = Reg(UInt(p(NastiKey).idBits.W))
  val rId = Reg(UInt(p(NastiKey).idBits.W))
  val rData = Reg(UInt(nastiXDataBits.W))
  val wData = Reg(UInt(nastiXDataBits.W))
  val wAddr = Reg(UInt(log2Up(numRegs).W))
  val rAddr = Reg(UInt(log2Up(numRegs).W))
  val wStrb = Reg(UInt(nastiWStrobeBits.W))

  when(io.nasti.aw.fire()){
    awFired := true.B
    wAddr := io.nasti.aw.bits.addr >> log2Up(nastiWStrobeBits)
    bId := io.nasti.aw.bits.id
    assert(io.nasti.aw.bits.len === 0.U)
  }

  when(io.nasti.w.fire()){
    wFired := true.B
    wData := io.nasti.w.bits.data
    wStrb := io.nasti.w.bits.strb
  }

  when(io.nasti.ar.fire()) {
    arFired := true.B
    rAddr := (io.nasti.ar.bits.addr >> log2Up(nastiWStrobeBits))(log2Up(numRegs)-1,0)
    rId := io.nasti.ar.bits.id
    assert(io.nasti.ar.bits.len === 0.U, "MCRFile only support single beat reads")
  }

  when(io.nasti.r.fire()) {
    arFired := false.B
  }

  when(io.nasti.b.fire()) {
    awFired := false.B
    wFired := false.B
    wCommited := false.B
  }

  when(io.mcr.write(wAddr).fire()){
    wCommited := true.B
  }

  io.mcr.write foreach { w => w.valid := false.B; w.bits := wData }
  io.mcr.read foreach { _.ready := false.B }
  io.mcr.write(wAddr).valid := awFired && wFired && ~wCommited
  io.mcr.read(rAddr).ready := arFired && io.nasti.r.ready

  io.nasti.r.bits := NastiReadDataChannel(rId, io.mcr.read(rAddr).bits)
  io.nasti.r.valid := arFired && io.mcr.read(rAddr).valid

  io.nasti.b.bits := NastiWriteResponseChannel(bId)
  io.nasti.b.valid := awFired && wFired && wCommited

  io.nasti.ar.ready := ~arFired
  io.nasti.aw.ready := ~awFired
  io.nasti.w.ready := ~wFired
}
