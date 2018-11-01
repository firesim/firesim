package firesim
package endpoints

import midas.core._
import midas.widgets._

import chisel3.core._
import chisel3.util._
import DataMirror.directionOf
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.PeripheryBusKey
import sifive.blocks.devices.uart.{UARTPortIO, PeripheryUARTKey}

class SimUART extends Endpoint {
  def matchType(data: Data) = data match {
    case channel: UARTPortIO =>
      directionOf(channel.txd) == ActualDirection.Output
    case _ => false
  }
  def widget(p: Parameters) = {
    val frequency = p(PeripheryBusKey).frequency
    val baudrate = 3686400L
    val div = (p(PeripheryBusKey).frequency / baudrate).toInt
    new UARTWidget(div)(p)
  }
  override def widgetName = "UARTWidget"
}

class UARTWidgetIO(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new UARTPortIO))
  val dma = None
  val address = None
}

class UARTWidget(div: Int)(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new UARTWidgetIO)

  val txfifo = Module(new Queue(UInt(8.W), 128))
  val rxfifo = Module(new Queue(UInt(8.W), 128))

  val target = io.hPort.hBits
  val fire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid & txfifo.io.enq.ready
  val targetReset = fire & io.tReset.bits
  rxfifo.reset := reset.toBool || targetReset
  txfifo.reset := reset.toBool || targetReset

  io.hPort.toHost.hReady := fire
  io.hPort.fromHost.hValid := fire
  io.tReset.ready := fire

  val sTxIdle :: sTxWait :: sTxData :: sTxBreak :: Nil = Enum(UInt(), 4)
  val txState = RegInit(sTxIdle)
  val txData = Reg(UInt(8.W))
  // iterate through bits in byte to deserialize
  val (txDataIdx, txDataWrap) = Counter(txState === sTxData && fire, 8)
  // iterate using div to convert clock rate to baud
  val (txBaudCount, txBaudWrap) = Counter(txState === sTxWait && fire, div)
  val (txSlackCount, txSlackWrap) = Counter(txState === sTxIdle && target.txd === 0.U && fire, 4)

  switch(txState) {
    is(sTxIdle) {
      when(txSlackWrap) {
        txData  := 0.U
        txState := sTxWait
      }
    }
    is(sTxWait) {
      when(txBaudWrap) {
        txState := sTxData
      }
    }
    is(sTxData) {
      when(fire) {
        txData := txData | (target.txd << txDataIdx)
      }
      when(txDataWrap) {
        txState := Mux(target.txd === 1.U, sTxIdle, sTxBreak)
      }.elsewhen(fire) {
        txState := sTxWait
      }
    }
    is(sTxBreak) {
      when(target.txd === 1.U && fire) {
        txState := sTxIdle
      }
    }
  }

  txfifo.io.enq.bits  := txData
  txfifo.io.enq.valid := txDataWrap

  val sRxIdle :: sRxStart :: sRxData :: Nil = Enum(UInt(), 3)
  val rxState = RegInit(sRxIdle)
  // iterate using div to convert clock rate to baud
  val (rxBaudCount, rxBaudWrap) = Counter(fire, div)
  // iterate through bits in byte to deserialize
  val (rxDataIdx, rxDataWrap) = Counter(rxState === sRxData && fire && rxBaudWrap, 8)

  target.rxd := 1.U
  switch(rxState) {
    is(sRxIdle) {
      target.rxd := 1.U
      when (rxBaudWrap && rxfifo.io.deq.valid) {
        rxState := sRxStart
      }
    }
    is(sRxStart) {
      target.rxd := 0.U
      when(rxBaudWrap) {
        rxState := sRxData
      }
    }
    is(sRxData) {
      target.rxd := (rxfifo.io.deq.bits >> rxDataIdx)(0)
      when(rxDataWrap && rxBaudWrap) {
        rxState := sRxIdle
      }
    }
  }
  rxfifo.io.deq.ready := (rxState === sRxData) && rxDataWrap && rxBaudWrap && fire

  genROReg(txfifo.io.deq.bits, "out_bits")
  genROReg(txfifo.io.deq.valid, "out_valid")
  Pulsify(genWORegInit(txfifo.io.deq.ready, "out_ready", false.B), pulseLength = 1)

  genWOReg(rxfifo.io.enq.bits, "in_bits")
  Pulsify(genWORegInit(rxfifo.io.enq.valid, "in_valid", false.B), pulseLength = 1)
  genROReg(rxfifo.io.enq.ready, "in_ready")

  genCRFile()
}
