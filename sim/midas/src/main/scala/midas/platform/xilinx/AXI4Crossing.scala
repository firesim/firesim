package midas.platform.xilinx

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import midas.stage.GoldenGateOutputFileAnnotation

/** An AXI4 bundle definition whose names should match the interfaces exposed on Xilinx IP blocks. aresetn and clock are
  * omitted, and no user fields are provided.
  */
class XilinxAXI4Bundle(val bundleParams: AXI4BundleParameters, val isAXI4Lite: Boolean = false) extends Bundle {
  //TODO: User fields
  require(bundleParams.echoFields == Nil)
  require(bundleParams.requestFields == Nil)
  require(bundleParams.responseFields == Nil)
  require(!isAXI4Lite || (bundleParams.dataBits == 64 || bundleParams.dataBits == 32))

  def AXI4Only[T <: Data](field: T): Option[T] = if (isAXI4Lite) None else Some(field)
  def axi4LiteSize = log2Ceil(bundleParams.dataBits / 8)

  val awid    = AXI4Only(Output(UInt(bundleParams.idBits.W)))
  val awaddr  = Output(UInt(bundleParams.addrBits.W))
  val awlen   = AXI4Only(Output(UInt(AXI4Parameters.lenBits.W)))
  val awsize  = AXI4Only(Output(UInt(AXI4Parameters.sizeBits.W)))
  val awburst = AXI4Only(Output(UInt(AXI4Parameters.burstBits.W)))
  val awlock  = AXI4Only(Output(UInt(AXI4Parameters.lockBits.W)))
  val awcache = AXI4Only(Output(UInt(AXI4Parameters.cacheBits.W)))
  val awprot  = Output(UInt(AXI4Parameters.protBits.W))
  //val awregion = Output(UInt(AXI4Parameters.regionBits.W))
  val awqos   = AXI4Only(Output(UInt(AXI4Parameters.qosBits.W)))
  val awvalid = Output(Bool())
  val awready = Input(Bool())

  val wdata  = Output(UInt(bundleParams.dataBits.W))
  val wstrb  = Output(UInt((bundleParams.dataBits / 8).W))
  val wlast  = AXI4Only(Output(Bool()))
  val wvalid = Output(Bool())
  val wready = Input(Bool())

  val bid    = AXI4Only(Input(UInt(bundleParams.idBits.W)))
  val bresp  = Input(UInt(AXI4Parameters.respBits.W))
  val bvalid = Input(Bool())
  val bready = Output(Bool())

  val arid    = AXI4Only(Output(UInt(bundleParams.idBits.W)))
  val araddr  = Output(UInt(bundleParams.addrBits.W))
  val arlen   = AXI4Only(Output(UInt(AXI4Parameters.lenBits.W)))
  val arsize  = AXI4Only(Output(UInt(AXI4Parameters.sizeBits.W)))
  val arburst = AXI4Only(Output(UInt(AXI4Parameters.burstBits.W)))
  val arlock  = AXI4Only(Output(UInt(AXI4Parameters.lockBits.W)))
  val arcache = AXI4Only(Output(UInt(AXI4Parameters.cacheBits.W)))
  val arprot  = Output(UInt(AXI4Parameters.protBits.W))
  //val arregion = Output(UInt(AXI4Parameters.regionBits.W))
  val arqos   = AXI4Only(Output(UInt(AXI4Parameters.qosBits.W)))
  val arvalid = Output(Bool())
  val arready = Input(Bool())

  val rid    = AXI4Only(Input(UInt(bundleParams.idBits.W)))
  val rdata  = Input(UInt(bundleParams.dataBits.W))
  val rresp  = Input(UInt(AXI4Parameters.respBits.W))
  val rlast  = AXI4Only(Input(Bool()))
  val rvalid = Input(Bool())
  val rready = Output(Bool())

  // TODO: Better name? I really mean rocket-chip type
  def driveStandardAXI4(axi4: AXI4Bundle, axiClock: Clock, axiReset: Bool): Unit = {

    axi4.aw.bits.id    := awid.getOrElse(0.U)
    axi4.aw.bits.addr  := awaddr
    axi4.aw.bits.len   := awlen.getOrElse(0.U)
    axi4.aw.bits.size  := awsize.getOrElse(axi4LiteSize.U)
    axi4.aw.bits.burst := awburst.getOrElse(AXI4Parameters.BURST_INCR)
    axi4.aw.bits.lock  := awlock.getOrElse(0.U)
    axi4.aw.bits.cache := awcache.getOrElse(0.U)
    axi4.aw.bits.prot  := awprot
    axi4.aw.bits.qos   := awqos.getOrElse(0.U)
    axi4.aw.valid      := awvalid
    awready            := axi4.aw.ready

    axi4.w.bits.data := wdata
    axi4.w.bits.strb := wstrb
    axi4.w.bits.last := wlast.getOrElse(true.B)
    axi4.w.valid     := wvalid
    wready           := axi4.w.ready

    bid.foreach { _ := axi4.b.bits.id }
    bresp        := axi4.b.bits.resp
    bvalid       := axi4.b.valid
    axi4.b.ready := bready

    axi4.ar.bits.id    := arid.getOrElse(0.U)
    axi4.ar.bits.addr  := araddr
    axi4.ar.bits.len   := arlen.getOrElse(0.U)
    axi4.ar.bits.size  := arsize.getOrElse(axi4LiteSize.U)
    axi4.ar.bits.burst := arburst.getOrElse(AXI4Parameters.BURST_INCR)
    axi4.ar.bits.lock  := arlock.getOrElse(0.U)
    axi4.ar.bits.cache := arcache.getOrElse(0.U)
    axi4.ar.bits.prot  := arprot
    axi4.ar.bits.qos   := arqos.getOrElse(0.U)
    axi4.ar.valid      := arvalid
    arready            := axi4.ar.ready

    rid.foreach { _ := axi4.r.bits.id }
    rdata        := axi4.r.bits.data
    rresp        := axi4.r.bits.resp
    rlast.foreach { _ := axi4.r.bits.last }
    rvalid       := axi4.r.valid
    axi4.r.ready := rready
    if (isAXI4Lite) {
      withClockAndReset(axiClock, axiReset) {
        assert(!axi4.r.valid || axi4.r.bits.last)
      }
    }
  }

  def drivenByStandardAXI4(axi4: AXI4Bundle, axiClock: Clock, axiReset: Bool): Unit = {
    awid.foreach { _ := axi4.aw.bits.id }
    awaddr        := axi4.aw.bits.addr
    awlen.foreach { _ := axi4.aw.bits.len }
    awsize.foreach { _ := axi4.aw.bits.size }
    awburst.foreach { _ := axi4.aw.bits.burst }
    awlock.foreach { _ := axi4.aw.bits.lock }
    awcache.foreach { _ := axi4.aw.bits.cache }
    awprot        := axi4.aw.bits.prot
    awqos.foreach { _ := axi4.aw.bits.qos }
    awvalid       := axi4.aw.valid
    axi4.aw.ready := awready

    wdata        := axi4.w.bits.data
    wstrb        := axi4.w.bits.strb
    wlast.foreach { _ := axi4.w.bits.last }
    wvalid       := axi4.w.valid
    axi4.w.ready := wready

    axi4.b.bits.id   := bid.getOrElse(0.U)
    axi4.b.bits.resp := bresp
    axi4.b.valid     := bvalid
    bready           := axi4.b.ready

    arid.foreach { _ := axi4.ar.bits.id }
    araddr        := axi4.ar.bits.addr
    arlen.foreach { _ := axi4.ar.bits.len }
    arsize.foreach { _ := axi4.ar.bits.size }
    arburst.foreach { _ := axi4.ar.bits.burst }
    arlock.foreach { _ := axi4.ar.bits.lock }
    arcache.foreach { _ := axi4.ar.bits.cache }
    arprot        := axi4.ar.bits.prot
    arqos.foreach { _ := axi4.ar.bits.qos }
    arvalid       := axi4.ar.valid
    axi4.ar.ready := arready

    axi4.r.bits.id   := rid.getOrElse(0.U)
    axi4.r.bits.data := rdata
    axi4.r.bits.resp := rresp
    axi4.r.bits.last := rlast.getOrElse(true.B)
    axi4.r.valid     := rvalid
    rready           := axi4.r.ready

    if (isAXI4Lite) {
      withClockAndReset(axiClock, axiReset) {
        assert(!axi4.aw.valid || axi4.aw.bits.len === 0.U)
        assert(!axi4.aw.valid || axi4.aw.bits.size === axi4LiteSize.U)
        // Use a diplomatic widget to strip down the ID space to a single value
        assert(!axi4.aw.valid || axi4.aw.bits.id === 0.U)
        assert(!axi4.w.valid || axi4.w.bits.last)
        assert(!axi4.ar.valid || axi4.ar.bits.len === 0.U)
        // Use a diplomatic widget to strip down the ID space to a single value
        assert(!axi4.ar.valid || axi4.ar.bits.id === 0.U)
        assert(!axi4.ar.valid || axi4.ar.bits.size === axi4LiteSize.U)
      }
    }
  }
}

class AXI4ClockConverter(
  bundleParams:             AXI4BundleParameters,
  override val desiredName: String,
  isAXI4Lite:               Boolean = false,
) extends BlackBox {
  val io = IO(new Bundle {
    val s_axi_aclk    = Input(Clock())
    val s_axi_aresetn = Input(AsyncReset())
    val s_axi         = Flipped(new XilinxAXI4Bundle(bundleParams, isAXI4Lite))

    val m_axi_aclk    = Input(Clock())
    val m_axi_aresetn = Input(AsyncReset())
    val m_axi         = new XilinxAXI4Bundle(bundleParams, isAXI4Lite)
  })

  val protocolParam = if (isAXI4Lite) "AXI4LITE" else "AXI4"

  GoldenGateOutputFileAnnotation.annotateFromChisel(
    s"""|create_ip -name axi_clock_converter \\
        |          -vendor xilinx.com \\
        |          -library ip \\
        |          -version 2.1 \\
        |          -module_name ${desiredName}
        |
        |set_property -dict [list CONFIG.PROTOCOL {${protocolParam}} \\
        |                         CONFIG.ADDR_WIDTH {${bundleParams.addrBits}} \\
        |                         CONFIG.SYNCHRONIZATION_STAGES {3} \\
        |                         CONFIG.DATA_WIDTH {${bundleParams.dataBits}} \\
        |                         CONFIG.ID_WIDTH {${bundleParams.idBits}} \\
        |                         CONFIG.AWUSER_WIDTH {0} \\
        |                         CONFIG.ARUSER_WIDTH {0} \\
        |                         CONFIG.RUSER_WIDTH {0} \\
        |                         CONFIG.WUSER_WIDTH {0} \\
        |                         CONFIG.BUSER_WIDTH {0}] \\
        |             [get_ips ${desiredName}]
        |""".stripMargin,
    s".${desiredName}.ipgen.tcl",
  )
}
