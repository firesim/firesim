// See LICENSE for license details.

package midas.platform

import chisel3._
import junctions._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, LazyModule, LazyRawModuleImp, TransferSizes}
import midas.{AXIDebugLinkAnnotation, AutoILAUseAXIS, EnableAutoILA}
import org.chipsalliance.cde.config.{Field, Parameters}
import midas.core.HostMemChannelKey
import midas.widgets.CtrlNastiKey
import midas.stage.GoldenGateOutputFileAnnotation
import midas.platform.xilinx._

case object SlashHostMemoryBase extends Field[BigInt](BigInt("60000000000", 16))

/** SLASH supplies the host clock directly. No MMCM or clock-converter IP is instantiated in the Versal reconfigurable
  * partition.
  */
class SlashShim(implicit p: Parameters) extends PlatformShim {
  require(p(midas.core.HostMemNumChannels) == 1)
  require(!p(EnableAutoILA) || p(AutoILAUseAXIS), "SLASH AutoILA requires the AXIS debug transport")
  val control            = AXI4BundleParameters(p(CtrlNastiKey).addrBits, p(CtrlNastiKey).dataBits, p(CtrlNastiKey).idBits)
  val memory             = p(HostMemChannelKey).axi4BundleParams.copy(addrBits = 64)
  require(p(SlashHostMemoryBase) >= 0 && p(SlashHostMemoryBase) + p(HostMemChannelKey).size <= (BigInt(1) << 64))
  val useAXIDebug        = p(EnableAutoILA) && p(AutoILAUseAXIS)
  val externalCtrlParams = if (useAXIDebug) control.copy(addrBits = 22) else control
  val debugControl       = if (useAXIDebug) {
    val master     = AXI4MasterNode(
      Seq(
        AXI4MasterPortParameters(
          Seq(
            AXI4MasterParameters(name = "slash-control", id = IdRange(0, 1), maxFlight = Some(1))
          )
        )
      )
    )
    def slave(base: BigInt, mask: BigInt) = AXI4SlaveNode(
      Seq(
        AXI4SlavePortParameters(
          slaves    = Seq(
            AXI4SlaveParameters(
              address       = Seq(AddressSet(base, mask)),
              supportsRead  = TransferSizes(4, 4),
              supportsWrite = TransferSizes(4, 4),
              interleavedId = Some(0),
            )
          ),
          beatBytes = 4,
        )
      )
    )
    val widgets    = slave(0, 0xffff)
    val debugHub   = slave(0x200000, 0x1fffff)
    val debugAudit = slave(0x100000, 0xfff)
    val xbar       = LazyModule(new AXI4Xbar)
    xbar.node  := master
    widgets    := AXI4Buffer() := xbar.node
    debugHub   := AXI4Buffer() := xbar.node
    debugAudit := AXI4Buffer() := xbar.node
    Some((master, widgets, debugHub, debugAudit))
  } else None

  lazy val module = new LazyRawModuleImp(this) {
    override def provideImplicitClockToLazyChildren = true
    val ap_rst_n                                    = IO(Input(AsyncReset()))
    val ap_clk                                      = IO(Input(Clock()))
    val s_axi_lite                                  = IO(Flipped(new XilinxAXI4Bundle(externalCtrlParams, isAXI4Lite = true)))
    val host_mem_0                                  = IO(new XilinxAXI4Bundle(memory))
    val hostReset                                   = ResetSynchronizer(!ap_rst_n.asBool, ap_clk, initValue = true)
    childClock       := ap_clk
    childReset       := hostReset
    top.module.clock := ap_clk
    top.module.reset := hostReset
    val convert = Module(new AXI42NastiIdentityModule(control))
    debugControl match {
      case Some((master, widgets, debugHub, debugAudit)) =>
        s_axi_lite.driveStandardAXI4(master.out.head._1, ap_clk, hostReset)
        convert.io.axi4                <> widgets.in.head._1
        // Nasti has no response-code fields.
        widgets.in.head._1.b.bits.resp := 0.U
        widgets.in.head._1.r.bits.resp := 0.U
        val hub = Module(new SlashAXIDebugHub)
        hub.io.aclk           := ap_clk
        hub.io.aresetn        := !hostReset
        hub.io.s_axi_awregion := 0.U
        hub.io.s_axi_arregion := 0.U
        hub.io.s_axi.drivenByStandardAXI4(debugHub.in.head._1, ap_clk, hostReset)
        val audit = withClockAndReset(ap_clk, hostReset) {
          Module(new SlashDebugAXIAudit(debugAudit.in.head._1.params))
        }
        audit.io.axi                      <> debugAudit.in.head._1
        audit.io.hostAR                   := s_axi_lite.arvalid && s_axi_lite.arready && s_axi_lite.araddr(21)
        audit.io.hubAR                    := hub.io.s_axi.arvalid && hub.io.s_axi.arready
        audit.io.hubAddress               := hub.io.s_axi.araddr
        audit.io.hubR                     := hub.io.s_axi.rvalid && hub.io.s_axi.rready
        audit.io.hubData                  := hub.io.s_axi.rdata
        audit.io.hubResp                  := hub.io.s_axi.rresp
        audit.io.hubLast                  := hub.io.s_axi.rlast.get
        audit.io.axisRX                   := hub.io.s00_axis_tvalid && hub.io.s00_axis_tready
        audit.io.axisData                 := hub.io.s00_axis_tdata
        audit.io.axisLast                 := hub.io.s00_axis_tlast
        audit.io.axisTX                   := hub.io.m00_axis_tvalid && hub.io.m00_axis_tready
        // AutoILA connects these preserved wires to its wrapper after the
        // platform has been lowered. Explicit links also work without CIPS
        // inside this reconfigurable partition.
        def ilaLink(port: String, input: Boolean, width: Int = 1): UInt = {
          val signal = dontTouch(Wire(UInt(width.W)).suggestName("slash_debug_" + port))
          signal := DontCare
          chisel3.experimental.annotate(new chisel3.experimental.ChiselAnnotation {
            def toFirrtl = AXIDebugLinkAnnotation(signal.toTarget, port, input, width)
          })
          signal
        }
        ilaLink("S_AXIS_tdata", true, 32) := hub.io.m00_axis_tdata
        ilaLink("S_AXIS_tvalid", true)    := hub.io.m00_axis_tvalid
        ilaLink("S_AXIS_tlast", true)     := hub.io.m00_axis_tlast
        hub.io.m00_axis_tready            := ilaLink("S_AXIS_tready", false).asBool
        hub.io.s00_axis_tdata             := ilaLink("M_AXIS_tdata", false, 32)
        hub.io.s00_axis_tvalid            := ilaLink("M_AXIS_tvalid", false).asBool
        hub.io.s00_axis_tlast             := ilaLink("M_AXIS_tlast", false).asBool
        ilaLink("M_AXIS_tready", true)    := hub.io.s00_axis_tready
        ilaLink("aresetn", true)          := !hostReset
      case None                                          =>
        s_axi_lite.driveStandardAXI4(convert.io.axi4, ap_clk, hostReset)
        s_axi_lite.bresp := 0.U
        s_axi_lite.rresp := 0.U
    }
    top.module.ctrl <> convert.io.nasti
    if (useAXIDebug) {
      Seq(
        s_axi_lite.araddr,
        s_axi_lite.arvalid,
        s_axi_lite.arready,
        s_axi_lite.rdata,
        s_axi_lite.rvalid,
        s_axi_lite.rready,
      ).foreach { signal =>
        chisel3.experimental.annotate(new chisel3.experimental.ChiselAnnotation {
          def toFirrtl = midas.InternalFirrtlFpgaDebugAnnotation(signal.toNamed)
        })
      }
    }

    if (top.module.mem.nonEmpty) {
      val translated = Wire(Flipped(new XilinxAXI4Bundle(memory)))
      translated.drivenByStandardAXI4(top.module.mem.head, ap_clk, hostReset)
      translated.araddr := p(SlashHostMemoryBase).U(64.W) + top.module.mem.head.ar.bits.addr
      translated.awaddr := p(SlashHostMemoryBase).U(64.W) + top.module.mem.head.aw.bits.addr
      translated.arcache.foreach { _ := AXI4Parameters.CACHE_MODIFIABLE }
      translated.awcache.foreach { _ := AXI4Parameters.CACHE_MODIFIABLE }
      host_mem_0        <> translated
    } else host_mem_0.tieoffAsManager
    top.module.cpu_managed_axi4.foreach { axi =>
      axi.aw.valid := false.B
      axi.aw.bits  := 0.U.asTypeOf(axi.aw.bits)
      axi.w.valid  := false.B
      axi.w.bits   := 0.U.asTypeOf(axi.w.bits)
      axi.ar.valid := false.B
      axi.ar.bits  := 0.U.asTypeOf(axi.ar.bits)
      axi.b.ready  := true.B
      axi.r.ready  := true.B
    }
    top.module.fpga_managed_axi4.foreach { axi =>
      axi.ar.ready := false.B
      axi.aw.ready := false.B
      axi.w.ready  := false.B
      axi.r.bits   := 0.U.asTypeOf(axi.r.bits)
      axi.b.bits   := 0.U.asTypeOf(axi.b.bits)
      axi.r.valid  := false.B
      axi.b.valid  := false.B
    }
    GoldenGateOutputFileAnnotation.annotateFromChisel("// SLASH: no dynamic macros\n", ".defines.vh")
    GoldenGateOutputFileAnnotation.annotateFromChisel("# SLASH: no dynamic environment\n", ".env.tcl")
  }
}
