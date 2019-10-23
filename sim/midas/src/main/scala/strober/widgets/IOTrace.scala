// See LICENSE for license details.

package strober
package widgets

import midas.widgets._
import midas.core._
import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

class IOTraceWidgetIO(
     wireInNum: Int,
     wireOutNum: Int,
     readyValidInputs: Seq[(String, ReadyValidIO[Data])],
     readyValidOutputs: Seq[(String, ReadyValidIO[Data])])
    (implicit p: Parameters) extends WidgetIO()(p) {
  val traceLen = Output(UInt(ctrl.nastiXDataBits.W))
  val wireIns = Flipped(Vec(wireInNum, Decoupled(UInt(ctrl.nastiXDataBits.W))))
  val wireOuts = Flipped(Vec(wireOutNum, Decoupled(UInt(ctrl.nastiXDataBits.W))))
  val readyValidIns = Flipped(new ReadyValidTraceRecord(readyValidInputs))
  val readyValidOuts = Flipped(new ReadyValidTraceRecord(readyValidOutputs))
}

class IOTraceWidget(
     wireIns: Seq[(String, Int)],
     wireOuts: Seq[(String, Int)],
     readyValidIns: Seq[(String, ReadyValidIO[Data])],
     readyValidOuts: Seq[(String, ReadyValidIO[Data])])
    (implicit p: Parameters) extends Widget()(p) {
  val numWireInChannels = wireIns.unzip._2 reduce (_ + _)
  val numWireOutChannels = wireOuts.unzip._2 reduce (_ + _)
  val io = IO(new IOTraceWidgetIO(
    numWireInChannels, numWireOutChannels, readyValidIns, readyValidOuts))

  ///*** Wire Traces ***/
  //def bindWireIns = bindChannels((name, offset) => {
  //  attachDecoupledSource(io.wireIns(offset), s"${name}_trace")
  //}) _

  //def bindWireOuts = bindChannels((name, offset) => {
  //  attachDecoupledSource(io.wireOuts(offset), s"${name}_trace")
  //}) _

  //val wireInAddrs = bindWireIns(wireIns, 0)
  //val wireOutAddrs = bindWireOuts(wireOuts, 0)

  ///*** ReadyValidIO Traces **/
  //val readyValidInPins = io.readyValidIns.elements.toSeq.unzip._2
  //val readyValidOutPins = io.readyValidOuts.elements.toSeq.unzip._2

  //def bindValidIns = bindChannels((name, offset) => {
  //  attachDecoupledSource(readyValidInPins(offset).valid, s"${name}_valid_trace")
  //}) _

  //def bindReadyIns = bindChannels((name, offset) => {
  //  attachDecoupledSource(readyValidInPins(offset).ready, s"${name}_ready_trace")
  //}) _

  //def bindValidOuts = bindChannels((name, offset) => {
  //  attachDecoupledSource(readyValidOutPins(offset).valid, s"${name}_valid_trace")
  //}) _

  //def bindReadyOuts = bindChannels((name, offset) => {
  //  attachDecoupledSource(readyValidOutPins(offset).ready, s"${name}_ready_trace")
  //}) _

  //val validInAddrs = bindValidIns(readyValidIns.unzip._1 map (name => name -> 1), 0)
  //val readyInAddrs = bindReadyIns(readyValidIns.unzip._1 map (name => name -> 1), 0)
  //val validOutAddrs = bindValidOuts(readyValidOuts.unzip._1 map (name => name -> 1), 0)
  //val readyOutAddrs = bindReadyOuts(readyValidOuts.unzip._1 map (name => name -> 1), 0)

  //def getFields[T <: Data](arg: (String, ReadyValidIO[T])) = {
  //  val (name, rv) = arg
  //  val (ins, outs) = SimUtils.parsePorts(rv.bits, prefix = s"${name}_bits")
  //  ins ++ outs
  //}

  //val bitsInFields = readyValidIns map getFields
  //val bitsOutFields = readyValidOuts map getFields

  //val bitsInChunks = readyValidIns map { case (name, rv) =>
  //  name -> ((rv.bits.getWidth - 1) / io.ctrl.nastiXDataBits + 1) }
  //val bitsOutChunks = readyValidOuts map { case (name, rv) =>
  //  name -> ((rv.bits.getWidth - 1) / io.ctrl.nastiXDataBits + 1) }

  //def genBitsBuffers[T <: Data](arg: ((String, Int), ReadyValidTraceIO[T])) = {
  //  val ((name, chunks), rv) = arg
  //  val buffers = Seq.fill(chunks)(Module(new Queue(UInt(io.ctrl.nastiXDataBits.W), 2)))
  //  rv.bits.ready := (buffers.zipWithIndex foldLeft true.B){ case (ready, (buffer, i)) =>
  //    val high = (((i + 1) * io.ctrl.nastiXDataBits) min rv.bits.bits.getWidth) - 1
  //    val low = i * io.ctrl.nastiXDataBits
  //    buffer suggestName s"${name}_bits_buffer_${i}"
  //    buffer.io.enq.bits := rv.bits.bits.asUInt()(high, low)
  //    buffer.io.enq.valid := rv.bits.fire()
  //    ready && buffer.io.enq.ready
  //  }
  //  buffers map (_.io.deq)
  //}

  //val bitsInBuffers = (bitsInChunks zip readyValidInPins) flatMap genBitsBuffers
  //val bitsOutBuffers = (bitsOutChunks zip readyValidOutPins) flatMap genBitsBuffers

  //def bindBitsIn = bindChannels((name, offset) => {
  //  attachDecoupledSource(bitsInBuffers(offset), s"${name}_bits_trace")
  //}) _

  //def bindBitsOut = bindChannels((name, offset) => {
  //  attachDecoupledSource(bitsOutBuffers(offset), s"${name}_bits_trace")
  //}) _

  //val bitsInAddrs = bindBitsIn(bitsInChunks, 0)
  //val bitsOutAddrs = bindBitsOut(bitsOutChunks, 0)

  //val traceLen = RegInit(128.U)
  //val traceLenAddr = attach(traceLen, "TRACELEN")
  //io.traceLen := traceLen

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._

  //  sb.append(genComment("Wire Input Traces"))
  //  sb.append(genMacro("IN_TR_SIZE", UInt32(wireIns.size)))
  //  sb.append(genArray("IN_TR_ADDRS", wireInAddrs map (off => UInt32(base + off))))
  //  sb.append(genArray("IN_TR_NAMES", wireIns.unzip._1 map CStrLit))
  //  sb.append(genArray("IN_TR_CHUNKS", wireIns.unzip._2 map (UInt32(_))))

  //  sb.append(genComment("Wire Output Traces"))
  //  sb.append(genMacro("OUT_TR_SIZE", UInt32(wireOuts.size)))
  //  sb.append(genArray("OUT_TR_ADDRS", wireOutAddrs map (off => UInt32(base + off))))
  //  sb.append(genArray("OUT_TR_NAMES", wireOuts.unzip._1 map CStrLit))
  //  sb.append(genArray("OUT_TR_CHUNKS", wireOuts.unzip._2 map (UInt32(_))))

  //  sb.append(genComment("ReadyValidIO Input Traces"))
  //  sb.append(genMacro("IN_TR_READY_VALID_SIZE", UInt32(readyValidIns.size)))
  //  sb.append(genArray("IN_TR_READY_VALID_NAMES", readyValidIns.unzip._1 map CStrLit))
  //  sb.append(genArray("IN_TR_VALID_ADDRS", validInAddrs map (off => UInt32(base + off))))
  //  sb.append(genArray("IN_TR_READY_ADDRS", readyInAddrs map (off => UInt32(base + off))))
  //  sb.append(genArray("IN_TR_BITS_ADDRS", bitsInAddrs map (off => UInt32(base + off))))
  //  sb.append(genArray("IN_TR_BITS_CHUNKS", bitsInChunks.unzip._2 map (UInt32(_))))
  //  sb.append(genArray("IN_TR_BITS_FIELD_NUMS", bitsInFields map (x => UInt32(x.size))))
  //  sb.append(genArray("IN_TR_BITS_FIELD_WIDTHS", bitsInFields.flatten.unzip._1 map (x => UInt32(x.getWidth))))
  //  sb.append(genArray("IN_TR_BITS_FIELD_NAMES", bitsInFields.flatten.unzip._2 map CStrLit))

  //  sb.append(genComment("ReadyValidIO Output Traces"))
  //  sb.append(genMacro("OUT_TR_READY_VALID_SIZE", UInt32(readyValidOuts.size)))
  //  sb.append(genArray("OUT_TR_READY_VALID_NAMES", readyValidOuts.unzip._1 map CStrLit))
  //  sb.append(genArray("OUT_TR_VALID_ADDRS", validOutAddrs map (off => UInt32(base + off))))
  //  sb.append(genArray("OUT_TR_READY_ADDRS", readyOutAddrs map (off => UInt32(base + off))))
  //  sb.append(genArray("OUT_TR_BITS_ADDRS", bitsOutAddrs map (off => UInt32(base + off))))
  //  sb.append(genArray("OUT_TR_BITS_CHUNKS", bitsOutChunks.unzip._2 map (UInt32(_))))
  //  sb.append(genArray("OUT_TR_BITS_FIELD_NUMS", bitsOutFields map (x => UInt32(x.size))))
  //  sb.append(genArray("OUT_TR_BITS_FIELD_WIDTHS", bitsOutFields.flatten.unzip._1 map (x => UInt32(x.getWidth))))
  //  sb.append(genArray("OUT_TR_BITS_FIELD_NAMES", bitsOutFields.flatten.unzip._2 map CStrLit))

  //  sb.append(genMacro("TRACELEN_ADDR", UInt32(base+traceLenAddr)))
  //  sb.append(genMacro("TRACE_MAX_LEN", UInt32(BigInt(p(strober.core.TraceMaxLen)))))
  }

  genCRFile()
}
