//See LICENSE for license details
package firesim.bridges

import midas.widgets._

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.PeripheryBusKey
import sifive.blocks.devices.uart.{UARTPortIO, UARTParams}

//Note: This file is heavily commented as it serves as a bridge walkthrough
//example in the FireSim docs

// DOC include start: UART Bridge Target-Side Interface
class UARTBridgeTargetIO(val uParams: UARTParams) extends Bundle {
  val clock = Input(Clock())
  val uart = Flipped(new UARTPortIO(uParams))
  // Note this reset is optional and used only to reset target-state modelled
  // in the bridge This reset just like any other Bool included in your target
  // interface, simply appears as another Bool in the input token.
  val reset = Input(Bool())
}
// DOC include end: UART Bridge Target-Side Interface

// DOC include start: UART Bridge Constructor Arg
// Out bridge module constructor argument. This captures all of the extra
// metadata we'd like to pass to the host-side BridgeModule. Note, we need to
// use a single case class to do so, even if it is simply to wrap a primitive
// type, as is the case for UART (int)
case class UARTKey(uParams: UARTParams, div: Int)
// DOC include end: UART Bridge Constructor Arg

// DOC include start: UART Bridge Target-Side Module
class UARTBridge(uParams: UARTParams)(implicit p: Parameters) extends BlackBox
    with Bridge[HostPortIO[UARTBridgeTargetIO], UARTBridgeModule] {
  // Since we're extending BlackBox this is the port will connect to in our target's RTL
  val io = IO(new UARTBridgeTargetIO(uParams))
  // Implement the bridgeIO member of Bridge using HostPort. This indicates that
  // we want to divide io, into a bidirectional token stream with the input
  // token corresponding to all of the inputs of this BlackBox, and the output token consisting of 
  // all of the outputs from the BlackBox
  val bridgeIO = HostPort(io)

  // Do some intermediate work to compute our host-side BridgeModule's constructor argument
  val frequency = p(PeripheryBusKey).dtsFrequency.get
  val baudrate = uParams.initBaudRate
  val div = (frequency / baudrate).toInt

  // And then implement the constructorArg member
  val constructorArg = Some(UARTKey(uParams, div))

  // Finally, and this is critical, emit the Bridge Annotations -- without
  // this, this BlackBox would appear like any other BlackBox to Golden Gate
  generateAnnotations()
}
// DOC include end: UART Bridge Target-Side Module

// DOC include start: UART Bridge Companion Object
object UARTBridge {
  def apply(clock: Clock, uart: UARTPortIO, reset: Bool)(implicit p: Parameters): UARTBridge = {
    val ep = Module(new UARTBridge(uart.c))
    ep.io.uart <> uart
    ep.io.clock := clock
    ep.io.reset := reset
    ep
  }
}
// DOC include end: UART Bridge Companion Object

// DOC include start: UART Bridge Header
// Our UARTBridgeModule definition, note:
// 1) it takes one parameter, key, of type UARTKey --> the same case class we captured from the target-side
// 2) It accepts one implicit parameter of type Parameters
// 3) It extends BridgeModule passing the type of the HostInterface
//
// While the Scala type system will check if you parameterized BridgeModule
// correctly, the types of the constructor arugument (in this case UARTKey),
// don't match, you'll only find out later when Golden Gate attempts to generate your module.
class UARTBridgeModule(key: UARTKey)(implicit p: Parameters) extends BridgeModule[HostPortIO[UARTBridgeTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    val div = key.div
    // This creates the interfaces for all of the host-side transport
    // AXI4-lite for the simulation control bus, =
    // AXI4 for DMA
    val io = IO(new WidgetIO())

    // This creates the host-side interface of your TargetIO
    val hPort = IO(HostPort(new UARTBridgeTargetIO(key.uParams)))

    // Generate some FIFOs to capture tokens...
    val txfifo = Module(new Queue(UInt(8.W), 128))
    val rxfifo = Module(new Queue(UInt(8.W), 128))

    val target = hPort.hBits.uart
    // In general, your BridgeModule will not need to do work every host-cycle. In simple Bridges,
    // we can do everything in a single host-cycle -- fire captures all of the
    // conditions under which we can consume and input token and produce a new
    // output token
    val fire = hPort.toHost.hValid && // We have a valid input token: toHost ~= leaving the transformed RTL
               hPort.fromHost.hReady && // We have space to enqueue a new output token
               txfifo.io.enq.ready      // We have space to capture new TX data
    val targetReset = fire & hPort.hBits.reset
    rxfifo.reset := reset.asBool || targetReset
    txfifo.reset := reset.asBool || targetReset

    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire
    // DOC include end: UART Bridge Header
    val sTxIdle :: sTxWait :: sTxData :: sTxBreak :: Nil = Enum(4)
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

    val sRxIdle :: sRxStart :: sRxData :: Nil = Enum(3)
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

    // DOC include start: UART Bridge Footer
    // Exposed the head of the queue and the valid bit as a read-only registers
    // with name "out_bits" and out_valid respectively
    genROReg(txfifo.io.deq.bits, "out_bits")
    genROReg(txfifo.io.deq.valid, "out_valid")

    // Generate a writeable register, "out_ready", that when written to dequeues
    // a single element in the tx_fifo. Pulsify derives the register back to false
    // after pulseLength cycles to prevent multiple dequeues
    Pulsify(genWORegInit(txfifo.io.deq.ready, "out_ready", false.B), pulseLength = 1)

    // Generate regisers for the rx-side of the UART; this is eseentially the reverse of the above
    genWOReg(rxfifo.io.enq.bits, "in_bits")
    Pulsify(genWORegInit(rxfifo.io.enq.valid, "in_valid", false.B), pulseLength = 1)
    genROReg(rxfifo.io.enq.ready, "in_ready")

    // This method invocation is required to wire up all of the MMIO registers to
    // the simulation control bus (AXI4-lite)
    genCRFile()
    // DOC include end: UART Bridge Footer
  }
}
