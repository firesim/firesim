//See LICENSE for license details
package firesim.bridges 

import midas.widgets._

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem.PeripheryBusKey
import chipyard.example.{JustReadParams, JustReadTopIO} 


class JustReadBridgeTargetIO() extends Bundle {
  val clock = Input(Clock())
  val justread = Flipped(new JustReadTopIO())
  // Note this reset is optional and used only to reset target-state modelled
  // in the bridge This reset just like any other Bool included in your target
  // interface, simply appears as another Bool in the input token.
  val reset = Input(Bool())
}

// case class JustReadKey(uParams: JustReadParams)

class JustReadBridge()(implicit p: Parameters) extends BlackBox
    with Bridge[HostPortIO[JustReadBridgeTargetIO], JustReadBridgeModule] { 
  // Since we're extending BlackBox this is the port will connect to in our target's RTL
  val io = IO(new JustReadBridgeTargetIO())
 
  val bridgeIO = HostPort(io)

  // And then implement the constructorArg member
  val constructorArg = None

  // Finally, and this is critical, emit the Bridge Annotations -- without
  // this, this BlackBox would appear like any other BlackBox to Golden Gate
  generateAnnotations()
}

object JustReadBridge {
  def apply(clock: Clock, justread: JustReadTopIO, reset: Bool)(implicit p: Parameters): JustReadBridge = {
    val ep = Module(new JustReadBridge())
    ep.io.justread.isReady := justread.isReady
   // ep.io.justread.load := justread.load
    ep.io.clock := clock
    ep.io.reset := reset
    ep
  } 
}

class JustReadBridgeModule()(implicit p: Parameters) extends BridgeModule[HostPortIO[JustReadBridgeTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    println("======= DN: JustReadBridgeModule lazy eval")
    // This creates the interfaces for all of the host-side transport
    // AXI4-lite for the simulation control bus, =
    // AXI4 for DMA
    val io = IO(new WidgetIO())

    // This creates the host-side interface of your TargetIO
    val hPort = IO(HostPort(new JustReadBridgeTargetIO()))

    // Generate some FIFOs to capture tokens...
    val fifo = Module(new Queue(UInt(8.W), 128))

    val target = hPort.hBits.justread
    // In general, your BridgeModule will not need to do work every host-cycle. In simple Bridges,
    // we can do everything in a single host-cycle -- fire captures all of the
    // conditions under which we can consume and input token and produce a new
    // output token
    val fire = hPort.toHost.hValid && // We have a valid input token: toHost ~= leaving the transformed RTL
               hPort.fromHost.hReady && // We have space to enqueue a new output token
               fifo.io.enq.ready      // We have space to capture new TX data
    val targetReset = fire & hPort.hBits.reset
    fifo.reset := reset.asBool || targetReset


    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire
    
    fifo.io.enq.valid := fire
    fifo.io.enq.bits := Cat(target.isReady, 0.U(7.W))
    //target.in := fifo.io.deq.bits
    //target.load := fifo.io.deq.valid

    genROReg(fifo.io.deq.bits, "out_bits")
    genROReg(fifo.io.deq.valid, "out_valid")
    Pulsify(genWORegInit(fifo.io.deq.ready, "out_ready", false.B), pulseLength = 1)

    genCRFile()
    // DOC include end: UART Bridge Footer

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(base, sb, "justread_t", "justread")
    }
  }
}
