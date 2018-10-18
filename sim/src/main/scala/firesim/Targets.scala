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
