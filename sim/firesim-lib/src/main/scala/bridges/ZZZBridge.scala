//See LICENSE for license details
package firesim.bridges 

import midas.widgets._

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem.PeripheryBusKey
import sifive.blocks.devices.zzz.{ZZZPortIO, ZZZParams} 

//Note: This file is heavily commented as it serves as a bridge walkthrough
//example in the FireSim docs

// DOC include start: UART Bridge Target-Side Interface
class ZZZBridgeTargetIO(val uParams: ZZZParams) extends Bundle {
  val clock = Input(Clock())
  val zzz = Flipped(new ZZZPortIO(uParams))
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
case class ZZZKey(uParams: ZZZParams, div: Int)
// DOC include end: UART Bridge Constructor Arg

// DOC include start: UART Bridge Target-Side Module
class ZZZBridge(uParams: ZZZParams)(implicit p: Parameters) extends BlackBox
    with Bridge[HostPortIO[ZZZBridgeTargetIO], ZZZBridgeModule] { 
  // Since we're extending BlackBox this is the port will connect to in our target's RTL
  val io = IO(new ZZZBridgeTargetIO(uParams))
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
  val constructorArg = Some(ZZZKey(uParams, div))

  // Finally, and this is critical, emit the Bridge Annotations -- without
  // this, this BlackBox would appear like any other BlackBox to Golden Gate
  generateAnnotations()
}
// DOC include end: UART Bridge Target-Side Module

// DOC include start: UART Bridge Companion Object
object ZZZBridge {
  def apply(clock: Clock, zzz: ZZZPortIO, reset: Bool)(implicit p: Parameters): ZZZBridge = {
    val ep = Module(new ZZZBridge(zzz.c))
    ep.io.zzz <> zzz
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
class ZZZBridgeModule(key: ZZZKey)(implicit p: Parameters) extends BridgeModule[HostPortIO[ZZZBridgeTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    println("======= DN: ZZZBridgeModule lazy eval")
    // This creates the interfaces for all of the host-side transport
    // AXI4-lite for the simulation control bus, =
    // AXI4 for DMA
    val io = IO(new WidgetIO())

    // This creates the host-side interface of your TargetIO
    val hPort = IO(HostPort(new ZZZBridgeTargetIO(key.uParams)))

    // Generate some FIFOs to capture tokens...
    val txfifo = Module(new Queue(UInt(8.W), 128))
    val rxfifo = Module(new Queue(UInt(8.W), 128))

    val target = hPort.hBits.zzz
    // In general, your BridgeModule will not need to do work every host-cycle. In simple Bridges,
    // we can do everything in a single host-cycle -- fire captures all of the
    // conditions under which we can consume and input token and produce a new
    // output token
    val fire = hPort.toHost.hValid && // We have a valid input token: toHost ~= leaving the transformed RTL
               hPort.fromHost.hReady && // We have space to enqueue a new output token
               txfifo.io.enq.ready      // We have space to capture new TX data
    // val targetReset = fire & hPort.hBits.reset
    
    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire
    // DOC include end: UART Bridge Header

    txfifo.io.enq.bits := target.out.bits
    txfifo.io.enq.valid := target.out.valid
    target.out.ready := txfifo.io.enq.ready

    target.in.bits := rxfifo.io.deq.bits
    target.in.valid := rxfifo.io.deq.valid
    rxfifo.io.deq.ready := target.in.ready

    when (rxfifo.io.enq.fire){
      printf("ZZZ Module TX (%x): %c\n", rxfifo.io.enq.bits, rxfifo.io.enq.bits)
    }

    genROReg(txfifo.io.deq.bits, "outDN_bits")
    genROReg(txfifo.io.deq.valid, "outDN_valid")
    Pulsify(genWORegInit(txfifo.io.deq.ready, "outDN_ready", false.B), pulseLength = 1)


    genWOReg(rxfifo.io.enq.bits, "inDN_bits")
    Pulsify(genWORegInit(rxfifo.io.enq.valid, "inDN_valid", false.B), pulseLength = 1)
    genROReg(rxfifo.io.enq.ready, "inDN_ready")

    // This method invocation is required to wire up all of the MMIO registers to
    // the simulation control bus (AXI4-lite)
    genCRFile()
    // DOC include end: UART Bridge Footer

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(base, sb, "zzz_t", "zzz")
    }
  }
}
