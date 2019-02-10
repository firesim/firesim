//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}

case object MemSize extends Field[Int]
case object NMemoryChannels extends Field[Int]
case object CacheBlockBytes extends Field[Int]
case object CacheBlockOffsetBits extends Field[Int]
case object Seed extends Field[Long]

// This module computes the sum of a simple singly linked-list, where each
// node consists of a pointer to the next node and a 64 bit SInt
// Inputs: (Decoupled) start address: the location of the first node in memory
// Outputs: (Decoupled) result: The sum of the list
class PointerChaser(implicit val p: Parameters) extends Module with HasNastiParameters {
  val io = IO(new Bundle {
    val nasti = new NastiIO
    val result = Decoupled(SInt(nastiXDataBits.W))
    val startAddr = Flipped(Decoupled(UInt(nastiXAddrBits.W)))
  })
  
  val memoryIF = io.nasti
  val busy = RegInit(false.B)
  val resultReg = RegInit(0.S)
  val resultValid = RegInit(false.B)
  
  val startFire = io.startAddr.valid && ~busy
  val doneFire =  io.result.valid && io.result.ready

  when (!resultValid && !busy) {
    busy := startFire
  }.elsewhen(doneFire) {
    busy := false.B
  }

  io.startAddr.ready := !busy

  io.result.bits := resultReg
  io.result.valid := resultValid

  val rFire = memoryIF.r.valid && memoryIF.r.ready
  val nextAddrAvailable = rFire && !memoryIF.r.bits.last

  // Need to add an extra cycle of delay between when we learn we are on
  // the last node and when the final sum is computed. Since the address beat
  // is returned first
  val isFinalNode = RegInit(false.B)
  // next node addr == 0 -> terminal node
  when (nextAddrAvailable) {
    isFinalNode := memoryIF.r.bits.data === 0.U
  }

  when (rFire && memoryIF.r.bits.last){
    resultValid := isFinalNode
    resultReg := resultReg + memoryIF.r.bits.data.asSInt()
  }.elsewhen (doneFire) {
    resultValid := false.B
    resultReg := 0.S
  }
  
  val arFire = memoryIF.ar.ready && memoryIF.ar.valid

  val arRegAddr = RegInit(0.U)
  val arValid = RegInit(false.B)

  when (startFire | (nextAddrAvailable && memoryIF.r.bits.data =/= 0.U)) {
    arValid := true.B
    arRegAddr := Mux(startFire, io.startAddr.bits, memoryIF.r.bits.data)
  }.elsewhen(arFire) {
    arValid := false.B
  }

  memoryIF.ar.bits := NastiWriteAddressChannel(
    id = 0.U,
    len = 1.U,
    size = bytesToXSize((nastiXDataBits/8).U),
    addr = arRegAddr)
  memoryIF.ar.valid := arValid
  memoryIF.r.ready := true.B

  val rnd = new scala.util.Random(p(Seed))
  memoryIF.aw.bits := NastiWriteAddressChannel(
    id = rnd.nextInt(1 << nastiWIdBits).U,
    len = rnd.nextInt(1 << nastiXLenBits).U,
    size = rnd.nextInt(1 << nastiXSizeBits).U,
    addr = rnd.nextInt.S.asUInt)
  memoryIF.aw.valid := false.B
  memoryIF.w.bits := NastiWriteDataChannel(rnd.nextLong.S.asUInt)
  memoryIF.w.valid := false.B
  memoryIF.b.ready := true.B

  println("MemSize " + p(MemSize))
  println("Number of Channels: " + p(NMemoryChannels))
  println("Cache Block Size: " + p(CacheBlockBytes))
  println("Number of Channels: " + p(NMemoryChannels))
}
