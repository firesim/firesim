package firesim.firesim

import chisel3._
import freechips.rocketchip._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tilelink._     
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.HeterogeneousBag
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy.LazyModule
import boom.system.{BoomSubsystem, BoomSubsystemModule}
import icenet._
import testchipip._
import testchipip.SerialAdapter.SERIAL_IF_WIDTH
import sifive.blocks.devices.uart._
import midas.widgets.AXI4BundleWithEdge
import java.io.File

/*******************************************************************************
* Top level DESIGN configurations. These describe the basic instantiations of
* the designs being simulated.
*
* In general, if you're adding or removing features from any of these, you
* should CREATE A NEW ONE, WITH A NEW NAME. This is because the manager
* will store this name as part of the tags for the AGFI, so that later you can
* reconstruct what is in a particular AGFI. These tags are also used to
* determine which driver to build.
*******************************************************************************/

class FireSim(implicit p: Parameters) extends RocketSubsystem
    with CanHaveMisalignedMasterAXI4MemPort
    with HasPeripheryBootROM
//    with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryIceNIC
    with HasPeripheryBlockDevice
{
  val hasTraces = rocketTiles.map(_.rocketParams.trace).reduce(_ || _)

  override lazy val module =
    if (hasTraces) new FireSimModuleImpTraced(this)
    else new FireSimModuleImp(this)

  // Error device used for testing and to NACK invalid front port transactions
  val error = LazyModule(new TLError(p(ErrorDeviceKey), sbus.beatBytes))
  // always buffer the error device because no one cares about its latency
  sbus.coupleTo("slave_named_error"){ error.node := TLBuffer() := _ } 
}

class FireSimModuleImp[+L <: FireSim](l: L) extends RocketSubsystemModuleImp(l)
    with HasRTCModuleImp
    with CanHaveMisalignedMasterAXI4MemPortModuleImp
    with HasPeripheryBootROMModuleImp
    // with HasExtInterruptsModuleImp
    with HasNoDebugModuleImp
    with HasPeripherySerialModuleImp
    with HasPeripheryUARTModuleImp
    with HasPeripheryIceNICModuleImpValidOnly
    with HasPeripheryBlockDeviceModuleImp

class FireSimModuleImpTraced[+L <: FireSim](l: L) extends FireSimModuleImp(l)
    with CanHaveRocketTraceIO

class FireSimNoNIC(implicit p: Parameters) extends RocketSubsystem
    with CanHaveMisalignedMasterAXI4MemPort
    with HasPeripheryBootROM
//    with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryBlockDevice
{
  val hasTraces = rocketTiles.map(_.rocketParams.trace).reduce(_ || _)

  override lazy val module =
    if (hasTraces) new FireSimNoNICModuleImpTraced(this)
    else new FireSimNoNICModuleImp(this)

  // Error device used for testing and to NACK invalid front port transactions
  val error = LazyModule(new TLError(p(ErrorDeviceKey), sbus.beatBytes))
  // always buffer the error device because no one cares about its latency
  sbus.coupleTo("slave_named_error"){ error.node := TLBuffer() := _ } 
}

class FireSimNoNICModuleImp[+L <: FireSimNoNIC](l: L) extends RocketSubsystemModuleImp(l)
    with HasRTCModuleImp
    with CanHaveMisalignedMasterAXI4MemPortModuleImp
    with HasPeripheryBootROMModuleImp
    // with HasExtInterruptsModuleImp
    with HasNoDebugModuleImp
    with HasPeripherySerialModuleImp
    with HasPeripheryUARTModuleImp
    with HasPeripheryBlockDeviceModuleImp

class FireSimNoNICModuleImpTraced[+L <: FireSimNoNIC](l: L) extends FireSimNoNICModuleImp(l)
    with CanHaveRocketTraceIO

class FireBoom(implicit p: Parameters) extends BoomSubsystem
    with CanHaveMisalignedMasterAXI4MemPort
    with HasPeripheryBootROM
//    with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryIceNIC
    with HasPeripheryBlockDevice
{
  val hasTraces = boomTiles.map(_.boomParams.trace).reduce(_ || _)

  override lazy val module = 
    if (hasTraces) new FireBoomModuleImpTraced(this)
    else new FireBoomModuleImp(this)

  // Error device used for testing and to NACK invalid front port transactions
  val error = LazyModule(new TLError(p(ErrorDeviceKey), sbus.beatBytes))
  // always buffer the error device because no one cares about its latency
  sbus.coupleTo("slave_named_error"){ error.node := TLBuffer() := _ } 
}

class FireBoomModuleImp[+L <: FireBoom](l: L) extends BoomSubsystemModule(l)
    with HasRTCModuleImp
    with CanHaveMisalignedMasterAXI4MemPortModuleImp
    with HasPeripheryBootROMModuleImp
    // with HasExtInterruptsModuleImp
    with HasNoDebugModuleImp
    with HasPeripherySerialModuleImp
    with HasPeripheryUARTModuleImp
    with HasPeripheryIceNICModuleImpValidOnly
    with HasPeripheryBlockDeviceModuleImp

class FireBoomModuleImpTraced[+L <: FireBoom](l: L) extends FireBoomModuleImp(l)
    with CanHaveBoomTraceIO

class FireBoomNoNIC(implicit p: Parameters) extends BoomSubsystem
    with CanHaveMisalignedMasterAXI4MemPort
    with HasPeripheryBootROM
//    with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryBlockDevice
{
  val hasTraces = boomTiles.map(_.boomParams.trace).reduce(_ || _)

  override lazy val module = 
    if (hasTraces) new FireBoomNoNICModuleImpTraced(this)
    else new FireBoomNoNICModuleImp(this)

  // Error device used for testing and to NACK invalid front port transactions
  val error = LazyModule(new TLError(p(ErrorDeviceKey), sbus.beatBytes))
  // always buffer the error device because no one cares about its latency
  sbus.coupleTo("slave_named_error"){ error.node := TLBuffer() := _ } 
}

class FireBoomNoNICModuleImp[+L <: FireBoomNoNIC](l: L) extends BoomSubsystemModule(l)
    with HasRTCModuleImp
    with CanHaveMisalignedMasterAXI4MemPortModuleImp
    with HasPeripheryBootROMModuleImp
    // with HasExtInterruptsModuleImp
    with HasNoDebugModuleImp
    with HasPeripherySerialModuleImp
    with HasPeripheryUARTModuleImp
    with HasPeripheryBlockDeviceModuleImp

class FireBoomNoNICModuleImpTraced[+L <: FireBoomNoNIC](l: L) extends FireBoomNoNICModuleImp(l)
    with CanHaveBoomTraceIO

case object NumNodes extends Field[Int]

class SupernodeIO(
      nNodes: Int,
      serialWidth: Int,
      bagPrototype: HeterogeneousBag[AXI4BundleWithEdge])(implicit p: Parameters)
    extends Bundle {

    val serial = Vec(nNodes, new SerialIO(serialWidth))
    val mem_axi = Vec(nNodes, bagPrototype.cloneType)
    val bdev = Vec(nNodes, new BlockDeviceIO)
    val net = Vec(nNodes, new NICIOvonly)
    val uart = Vec(nNodes, new UARTPortIO)

    override def cloneType = new SupernodeIO(nNodes, serialWidth, bagPrototype).asInstanceOf[this.type]
}


class FireSimSupernode(implicit p: Parameters) extends Module {
  val nNodes = p(NumNodes)
  val nodes = Seq.fill(nNodes) {
    Module(LazyModule(new FireSim).module)
  }

  val io = IO(new SupernodeIO(nNodes, SERIAL_IF_WIDTH, nodes(0).mem_axi4))

  io.mem_axi.zip(nodes.map(_.mem_axi4)).foreach {
    case (out, mem_axi4) => out <> mem_axi4
  }
  io.serial <> nodes.map(_.serial)
  io.bdev <> nodes.map(_.bdev)
  io.net <> nodes.map(_.net)
  io.uart <> nodes.map(_.uart(0))
  nodes.foreach{ case n => {
    n.debug.clockeddmi.get.dmi.req.valid := false.B
    n.debug.clockeddmi.get.dmi.resp.ready := false.B
    n.debug.clockeddmi.get.dmiClock := clock
    n.debug.clockeddmi.get.dmiReset := reset.toBool
    n.debug.clockeddmi.get.dmi.req.bits.data := DontCare
    n.debug.clockeddmi.get.dmi.req.bits.addr := DontCare
    n.debug.clockeddmi.get.dmi.req.bits.op := DontCare
  } }
}

