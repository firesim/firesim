// See LICENSE for license details.

package midas

import org.chipsalliance.cde.config.{Config, Parameters}
import midas.core._
import midas.platform.SlashShim
import midas.widgets.WidgetControlReservedBytes

/** Independent SLASH shell/runtime platform; preserves the existing AVED V80 config. */
class XilinxAlveoV80SlashConfig
    extends Config(new Config((_, _, _) => {
      case Platform                         => (p: Parameters) => new SlashShim()(p)
      case CPUManagedDDRTransport           => true
      case AutoILAUseAXIS                   => true
      case HostPayloadHoldEnabled           => true
      case HostTriggerSnapshotEnabled       => true
      case HostTargetOutputIsolationEnabled => true
      case HostClockGateModuleName          => "V80SlashClockGate"
      case CPUManagedAXI4Key                => Some(CPUManagedAXI4Params(32, 64, 1))
      case FPGAManagedAXI4Key               => None
      case StreamEngineInstantiatorKey      =>
        (e: StreamEngineParameters, p: Parameters) => new CPUManagedStreamEngine(p, e)
      case WidgetControlReservedBytes       => BigInt(0x40)
      case EnableModelMultiThreading        => false
      case HostMemChannelKey                => HostMemChannelParams(BigInt(8) << 30, 8, 16)
    }) ++ new VitisConfig)

/** Optional bidirectional rings mastered by the FPGA into host-observable card DDR. */
class WithDDRManagedBridgeStreams
    extends Config((_, _, _) => {
      case CPUManagedDDRTransport      => false
      case FPGAManagedDDRTransport     => true
      case StreamEngineInstantiatorKey =>
        (e: StreamEngineParameters, p: Parameters) => new DDRManagedStreamEngine(p, e)
    })

/** Low-bandwidth optional register transport, independent of DDR DMA staging. */
class WithMMIOManagedBridgeStreams
    extends Config((_, _, _) => {
      case CPUManagedDDRTransport      => false
      case FPGAManagedDDRTransport     => false
      case CPUManagedMMIOTransport     => true
      case CPUManagedAXI4Key           => Some(CPUManagedAXI4Params(32, 32, 1))
      case StreamEngineInstantiatorKey =>
        (e: StreamEngineParameters, p: Parameters) => new CPUManagedStreamEngine(p, e)
    })
