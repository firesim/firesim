package firesim.firesim

import chisel3._
import freechips.rocketchip._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.config.Parameters
import boom.system.{BoomSubsystem, BoomSubsystemModule}
import icenet._
import testchipip._
import sifive.blocks.devices.uart._
import java.io.File
import freechips.rocketchip.rocket.TracedInstruction
import firesim.endpoints.TraceOutputTop

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
{
  val traced_params = outer.rocketTiles(0).p
  val tile_traces = outer.rocketTiles flatMap (tile => tile.module.trace.get)
  val traceIO = IO(Output(new TraceOutputTop(tile_traces.length)(traced_params)))
  traceIO.traces zip tile_traces foreach ({ case (ioconnect, trace) => ioconnect := trace })
}



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
{
  val traced_params = outer.boomTiles(0).p
  val tile_traces = outer.boomTiles flatMap (tile => tile.module.trace.get)
  val traceIO = IO(Output(new TraceOutputTop(tile_traces.length)(traced_params)))
  traceIO.traces zip tile_traces foreach ({ case (ioconnect, trace) => ioconnect := trace })
}
