package firesim.firesim

import chisel3._
import chisel3.Module
import chisel3.experimental.RawModule
import chisel3.internal.firrtl.Port
import testchipip._
import freechips.rocketchip._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.config.Parameters
import boom.system.{BoomSubsystem, BoomSubsystemModule}
import freechips.rocketchip.util.{GeneratorApp, ParsedInputNames, HeterogeneousBag}
import icenet._
import testchipip._
import sifive.blocks.devices.uart._
import java.io.File
import freechips.rocketchip.config.{Field, Parameters}
import testchipip.SerialAdapter.SERIAL_IF_WIDTH


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
    with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryIceNIC
    with HasPeripheryBlockDevice
{
  override lazy val module = new FireSimModuleImp(this)
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

class FireSimNoNIC(implicit p: Parameters) extends RocketSubsystem
    with CanHaveMisalignedMasterAXI4MemPort
    with HasPeripheryBootROM
    with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryBlockDevice
{
  override lazy val module = new FireSimNoNICModuleImp(this)
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


class FireBoom(implicit p: Parameters) extends BoomSubsystem
    with CanHaveMisalignedMasterAXI4MemPort
    with HasPeripheryBootROM
    with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryIceNIC
    with HasPeripheryBlockDevice
{
  override lazy val module = new FireBoomModuleImp(this)
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

class FireBoomNoNIC(implicit p: Parameters) extends BoomSubsystem
    with CanHaveMisalignedMasterAXI4MemPort
    with HasPeripheryBootROM
    with HasSystemErrorSlave
    // with HasSyncExtInterrupts
    with HasNoDebug
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryBlockDevice
{
  override lazy val module = new FireBoomNoNICModuleImp(this)
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

case object NumNodes extends Field[Int]

class SupernodeTopIO(
      nNodes: Int,
      serialWidth: Int,
      bagPrototype: HeterogeneousBag[AXI4Bundle])(implicit p: Parameters)
    extends Bundle {

    val serial = Vec(nNodes, new SerialIO(serialWidth))
    val mem_axi = Vec(nNodes, bagPrototype.cloneType)
    val bdev = Vec(nNodes, new BlockDeviceIO)
    val net = Vec(nNodes, new NICIOvonly)
    val uart = Vec(nNodes, new UARTPortIO)

    override def cloneType = new SupernodeTopIO(nNodes, serialWidth, bagPrototype).asInstanceOf[this.type]
}


class SupernodeTop(implicit p: Parameters) extends Module {
  val nNodes = p(NumNodes)
  val nodes = Seq.fill(nNodes) {
    Module(LazyModule(new FireSim).module)
  }

  val io = IO(new SupernodeTopIO(nNodes, SERIAL_IF_WIDTH, nodes(0).mem_axi4))

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
