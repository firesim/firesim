// //See LICENSE for license details
// package firesim.bridges 

// import midas.widgets._

// import chisel3._
// //import chisel3.stage.ChiselStage
// import chisel3.util._
// import org.chipsalliance.cde.config.Parameters
// //import freechips.rocketchip.subsystem.PeripheryBusKey
// //import chipyard.example.{JustPlayParams, JustPlayTopIO}
// import sifive.blocks.inclusivecache.{JustL2TopIO}
// //import chipyard.example.JustPlayKey
 

// case class JustL2Params(
// 	address: BigInt = 0x2000,
// 	width: Int = 32)

// class JustL2BridgeTargetIO(implicit p: Parameters) extends Bundle {
//   val clock = Input(Clock())
//   val justL2 = Flipped(new JustL2TopIO)
//   // Note this reset is optional and used only to reset target-state modelled
//   // in the bridge This reset just like any other Bool included in your target
//   // interface, simply appears as another Bool in the input token.
//   val reset = Input(Bool())
//   //println(f"JUSTPLAY Module: InclusiveCacheParameters = ${InclusiveCacheParameters.L2ControlAddress}%h")
// }

// case class JustL2Key(wParams: JustL2Params)

// class JustL2Bridge()(implicit p: Parameters) extends BlackBox
//     with Bridge[HostPortIO[JustL2BridgeTargetIO], JustL2BridgeModule] { 
//   // Since we're extending BlackBox this is the port will connect to in our target's RTL
//   val io = IO(new JustL2BridgeTargetIO)
 
//   val bridgeIO = HostPort(io)

//   // And then implement the constructorArg member
//   val constructorArg = None

//   // Finally, and this is critical, emit the Bridge Annotations -- without
//   // this, this BlackBox would appear like any other BlackBox to Golden Gate
//   generateAnnotations()
// }

// object JustL2Bridge {
//   def apply(clock: Clock, justL2: JustL2TopIO, reset: Bool)(implicit p: Parameters): JustL2Bridge = {
//     val ep = Module(new JustL2Bridge)
//     ep.io.justL2.out <> justL2.out
//     ep.io.justL2.in <> justL2.in
//     ep.io.clock := clock
//     ep.io.reset := reset
//     ep
//   } 
// }

// class JustL2BridgeModule(key: JustL2Key)(implicit p: Parameters) extends BridgeModule[HostPortIO[JustL2BridgeTargetIO]]()(p) {
//   lazy val module = new BridgeModuleImp(this) {
//     println("======= DN: JustL2BridgeModule lazy eval")
//     // This creates the interfaces for all of the host-side transport
//     // AXI4-lite for the simulation control bus, =
//     // AXI4 for DMA
//     val io = IO(new WidgetIO())

//     // This creates the host-side interface of your TargetIO
//     val hPort = IO(HostPort(new JustL2BridgeTargetIO))

//     // Generate some FIFOs to capture tokens...
//     val outfifo = Module(new Queue(UInt(key.wParams.width.W), 128))
//     val infifo  = Module(new Queue(UInt(key.wParams.width.W), 128))

//     val target = hPort.hBits.justL2
//     // In general, your BridgeModule will not need to do work every host-cycle. In simple Bridges,
//     // we can do everything in a single host-cycle -- fire captures all of the
//     // conditions under which we can consume and input token and produce a new
//     // output token
//     val dowait = RegInit(false.B)
//     val fire = hPort.toHost.hValid && // We have a valid input token: toHost ~= leaving the transformed RTL
//                hPort.fromHost.hReady && // We have space to enqueue a new output token
//                outfifo.io.enq.ready &&     // We have space to capture new TX data (only condition from the bridge functionality to stall the simulation)
//                !dowait
//     val targetReset = fire & hPort.hBits.reset
//     outfifo.reset := reset.asBool || targetReset

//     // Important to send back tokens to the FireSim simulation and keep it running
//     hPort.toHost.hReady := fire
//     hPort.fromHost.hValid := fire
    
//     when (target.out.valid){
//       printf("JUSTPLAY Module: target.out.valid = true & target.out.bits = (%x): %d\n", target.out.bits, target.out.bits)
//       dowait := true.B
//     }
//     when (target.in.valid){
//       printf("JUSTPLAY Module: target.in.valid = true & target.in.bits = (%x): %d\n", target.in.bits, target.in.bits)
//       dowait := false.B
//     }
//     // when (target.in.valid){
//     //   printf("JUSTPLAY Module: target.in.valid = true & target.in.bits = (%x): %c\n", target.in.bits, target.in.bits)
//     //   printf("\t outfifo.io.enq.valid = %d\n", outfifo.io.enq.valid)  
//     // }
//     outfifo.io.enq.valid := fire && target.out.valid
//     outfifo.io.enq.bits := target.out.bits
//     target.out.ready := fire && outfifo.io.enq.ready
    
//     target.in.bits := infifo.io.deq.bits
//     target.in.valid := infifo.io.deq.valid
//     infifo.io.deq.ready := fire && target.in.ready

//     genROReg(outfifo.io.deq.bits, "out_bits")
//     genROReg(outfifo.io.deq.valid, "out_valid")
//     Pulsify(genWORegInit(outfifo.io.deq.ready, "out_ready", false.B), pulseLength = 1)

//     genWOReg(infifo.io.enq.bits, "in_bits")
//     Pulsify(genWORegInit(infifo.io.enq.valid, "in_valid", false.B), pulseLength = 1)
//     genROReg(infifo.io.enq.ready, "in_ready")

//     genCRFile()
//     // DOC include end: UART Bridge Footer

//     override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
//       genConstructor(base, sb, "justl2_t", "justl2")
//     }
//   }
// }
