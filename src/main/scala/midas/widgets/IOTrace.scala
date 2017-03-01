package midas
package widgets

import chisel3._
import chisel3.util._
import config.Parameters

class IOTraceWidgetIO(inNum: Int, outNum: Int)(implicit p: Parameters)
    extends WidgetIO()(p) {
  val traceLen = UInt(OUTPUT, width=ctrl.nastiXDataBits)
  val ins = Flipped(Vec(inNum, Decoupled(UInt(width=ctrl.nastiXDataBits))))
  val outs = Flipped(Vec(outNum, Decoupled(UInt(width=ctrl.nastiXDataBits))))
}

class IOTraceWidget(inputs: Seq[(String, Int)], outputs: Seq[(String, Int)])
    (implicit p: Parameters) extends Widget()(p) with HasChannels {
  val numInputChannels = inputs.unzip._2.reduce(_ + _)
  val numOutputChannels = outputs.unzip._2.reduce(_ + _)
  val io = IO(new IOTraceWidgetIO(numInputChannels, numOutputChannels))

  def bindInputs = bindChannels((name, offset) => {
    attachDecoupledSource(io.ins(offset), s"${name}_trace")
  }) _

  def bindOutputs = bindChannels((name, offset) => {
    attachDecoupledSource(io.outs(offset), s"${name}_trace")
  }) _

  val inputAddrs = bindInputs(inputs, 0)
  val outputAddrs = bindOutputs(outputs, 0)
  val traceLen = RegInit(UInt(128))
  val traceLenAddr = attach(traceLen, "TRACELEN")
  io.traceLen := traceLen

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._

    sb.append(genComment("Input Traces"))
    sb.append(genMacro("IN_TR_SIZE", UInt32(inputs.size)))
    sb.append(genArray("IN_TR_ADDRS", inputAddrs map (off => UInt32(base + off))))
    sb.append(genArray("IN_TR_NAMES", inputs.unzip._1 map CStrLit))
    sb.append(genArray("IN_TR_CHUNKS", inputs.unzip._2 map (UInt32(_))))

    sb.append(genComment("Output Traces"))
    sb.append(genMacro("OUT_TR_SIZE", UInt32(outputs.size)))
    sb.append(genArray("OUT_TR_ADDRS", outputAddrs map (off => UInt32(base + off))))
    sb.append(genArray("OUT_TR_NAMES", outputs.unzip._1 map CStrLit))
    sb.append(genArray("OUT_TR_CHUNKS", outputs.unzip._2 map (UInt32(_))))

    sb.append(genMacro("TRACELEN_ADDR", UInt32(base+traceLenAddr)))
    sb.append(genMacro("TRACE_MAX_LEN", UInt32(BigInt(p(midas.core.TraceMaxLen)))))
  }

  genCRFile()
}
