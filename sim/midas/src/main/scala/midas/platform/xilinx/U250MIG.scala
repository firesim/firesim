// See LICENSE for license details.
package midas.platform.xilinx

import chisel3._
import chisel3.experimental.{Analog,attach}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._

// TODO: This needs to be cleaned up before merge.
import midas.core.{HostMemChannelParams}
import midas.stage.GoldenGateOutputFileAnnotation


// These names are necessarily verbose because they need to match the names of
// the IO on the black box.
trait U250MIGIODDR { self: Bundle =>
  val c0_ddr4_adr           = Output(UInt(17.W))
  val c0_ddr4_bg            = Output(UInt(1.W))
  val c0_ddr4_ba            = Output(UInt(2.W))
  val c0_ddr4_reset_n       = Output(Bool())
  val c0_ddr4_act_n         = Output(Bool())
  val c0_ddr4_ck_c          = Output(Bool())
  val c0_ddr4_ck_t          = Output(Bool())
  val c0_ddr4_cke           = Output(Bool())
  val c0_ddr4_cs_n          = Output(Bool())
  val c0_ddr4_odt           = Output(Bool())

  val c0_ddr4_dq            = Analog(64.W)
  val c0_ddr4_dqs_c         = Analog(8.W)
  val c0_ddr4_dqs_t         = Analog(8.W)
  val c0_ddr4_dm_dbi_n      = Analog(8.W)
}

trait U250MIGIOClocksReset { self: Bundle =>
  //inputs
  val c0_sys_clk_p              = Input(Bool())
  val c0_sys_clk_n              = Input(Bool())
  //user interface signals
  val c0_ddr4_ui_clk            = Output(Clock())
  val c0_ddr4_ui_clk_sync_rst   = Output(Bool())
  val c0_ddr4_aresetn           = Input(Bool())
  //misc
  val c0_init_calib_complete    = Output(Bool())
  val sys_rst                   = Input(Bool())
}

class U250MIGBlackBoxIP(axi4DataBits: Int, axi4IdBits: Int) extends BlackBox {
  val io = IO(new Bundle with U250MIGIODDR with U250MIGIOClocksReset {
    // ECC control IF
    val c0_ddr4_s_axi_ctrl_awvalid = Input(Bool())
    val c0_ddr4_s_axi_ctrl_awready = Output(Bool())
    val c0_ddr4_s_axi_ctrl_awaddr = Input(UInt(32.W))
    val c0_ddr4_s_axi_ctrl_wvalid = Input(Bool())
    val c0_ddr4_s_axi_ctrl_wready = Output(Bool())
    val c0_ddr4_s_axi_ctrl_wdata = Input(UInt(32.W))
    val c0_ddr4_s_axi_ctrl_bvalid = Output(Bool())
    val c0_ddr4_s_axi_ctrl_bready = Input(Bool())
    val c0_ddr4_s_axi_ctrl_bresp = Output(UInt(2.W))
    val c0_ddr4_s_axi_ctrl_arvalid = Input(Bool())
    val c0_ddr4_s_axi_ctrl_arready = Output(Bool())
    val c0_ddr4_s_axi_ctrl_araddr = Input(UInt(32.W))
    val c0_ddr4_s_axi_ctrl_rvalid = Output(Bool())
    val c0_ddr4_s_axi_ctrl_rready = Input(Bool())
    val c0_ddr4_s_axi_ctrl_rdata = Output(UInt(32.W))
    val c0_ddr4_s_axi_ctrl_rresp = Output(UInt(2.W))
    //slave interface write address ports
    val c0_ddr4_s_axi_awid            = Input(UInt(4.W))
    val c0_ddr4_s_axi_awaddr          = Input(UInt(34.W))
    val c0_ddr4_s_axi_awlen           = Input(UInt(8.W))
    val c0_ddr4_s_axi_awsize          = Input(UInt(3.W))
    val c0_ddr4_s_axi_awburst         = Input(UInt(2.W))
    val c0_ddr4_s_axi_awlock          = Input(UInt(1.W))
    val c0_ddr4_s_axi_awcache         = Input(UInt(4.W))
    val c0_ddr4_s_axi_awprot          = Input(UInt(3.W))
    val c0_ddr4_s_axi_awqos           = Input(UInt(4.W))
    val c0_ddr4_s_axi_awvalid         = Input(Bool())
    val c0_ddr4_s_axi_awready         = Output(Bool())
    //slave interface write data ports
    val c0_ddr4_s_axi_wdata           = Input(UInt(axi4DataBits.W))
    val c0_ddr4_s_axi_wstrb           = Input(UInt((axi4DataBits/8).W))
    val c0_ddr4_s_axi_wlast           = Input(Bool())
    val c0_ddr4_s_axi_wvalid          = Input(Bool())
    val c0_ddr4_s_axi_wready          = Output(Bool())
    //slave interface write response ports
    val c0_ddr4_s_axi_bready          = Input(Bool())
    val c0_ddr4_s_axi_bid             = Output(UInt(4.W))
    val c0_ddr4_s_axi_bresp           = Output(UInt(2.W))
    val c0_ddr4_s_axi_bvalid          = Output(Bool())
    //slave interface read address ports
    val c0_ddr4_s_axi_arid            = Input(UInt(4.W))
    val c0_ddr4_s_axi_araddr          = Input(UInt(34.W))
    val c0_ddr4_s_axi_arlen           = Input(UInt(8.W))
    val c0_ddr4_s_axi_arsize          = Input(UInt(3.W))
    val c0_ddr4_s_axi_arburst         = Input(UInt(2.W))
    val c0_ddr4_s_axi_arlock          = Input(UInt(1.W))
    val c0_ddr4_s_axi_arcache         = Input(UInt(4.W))
    val c0_ddr4_s_axi_arprot          = Input(UInt(3.W))
    val c0_ddr4_s_axi_arqos           = Input(UInt(4.W))
    val c0_ddr4_s_axi_arvalid         = Input(Bool())
    val c0_ddr4_s_axi_arready         = Output(Bool())
    //slave interface read data ports
    val c0_ddr4_s_axi_rready          = Input(Bool())
    val c0_ddr4_s_axi_rid             = Output(UInt(4.W))
    val c0_ddr4_s_axi_rdata           = Output(UInt(axi4DataBits.W))
    val c0_ddr4_s_axi_rresp           = Output(UInt(2.W))
    val c0_ddr4_s_axi_rlast           = Output(Bool())
    val c0_ddr4_s_axi_rvalid          = Output(Bool())
  })

  GoldenGateOutputFileAnnotation.annotateFromChisel(
    s"""
      # This is probably set somewhere in FPGA shells.
      set ipdir migdir
      create_ip -vendor xilinx.com -library ip -version 2.2 -name ddr4 -module_name u250mig -dir $$ipdir -force
      set_property -dict [list \\
      CONFIG.AL_SEL                                      {0} \\
      CONFIG.C0.ADDR_WIDTH                               {17} \\
      CONFIG.C0.BANK_GROUP_WIDTH                         {2} \\
      CONFIG.C0.CKE_WIDTH                                {1} \\
      CONFIG.C0.CK_WIDTH                                 {1} \\
      CONFIG.C0.CS_WIDTH                                 {1} \\
      CONFIG.C0.ControllerType                           {DDR4_SDRAM} \\
      CONFIG.C0.DDR4_AUTO_AP_COL_A3                      {true} \\
      CONFIG.C0.DDR4_AutoPrecharge                       {false} \\
      CONFIG.C0.DDR4_AxiAddressWidth                     {34} \\
      CONFIG.C0.DDR4_AxiArbitrationScheme                {RD_PRI_REG} \\
      CONFIG.C0.DDR4_AxiDataWidth                        {${axi4DataBits}} \\
      CONFIG.C0.DDR4_AxiIDWidth                          {${axi4IdBits}} \\
      CONFIG.C0.DDR4_AxiNarrowBurst                      {true} \\
      CONFIG.C0.DDR4_AxiSelection                        {true} \\
      CONFIG.C0.DDR4_BurstLength                         {8} \\
      CONFIG.C0.DDR4_BurstType                           {Sequential} \\
      CONFIG.C0.DDR4_CLKFBOUT_MULT                       {5} \\
      CONFIG.C0.DDR4_CLKOUT0_DIVIDE                      {5} \\
      CONFIG.C0.DDR4_Capacity                            {512} \\
      CONFIG.C0.DDR4_CasLatency                          {17} \\
      CONFIG.C0.DDR4_CasWriteLatency                     {12} \\
      CONFIG.C0.DDR4_ChipSelect                          {true} \\
      CONFIG.C0.DDR4_Clamshell                           {false} \\
      CONFIG.C0.DDR4_CustomParts                         {no_file_loaded} \\
      CONFIG.C0.DDR4_DIVCLK_DIVIDE                       {1} \\
      CONFIG.C0.DDR4_DataMask                            {NONE} \\
      CONFIG.C0.DDR4_DataWidth                           {72} \\
      CONFIG.C0.DDR4_EN_PARITY                           {true} \\
      CONFIG.C0.DDR4_Ecc                                 {true} \\
      CONFIG.C0.DDR4_Enable_LVAUX                        {false} \\
      CONFIG.C0.DDR4_InputClockPeriod                    {3332} \\
      CONFIG.C0.DDR4_MCS_ECC                             {false} \\
      CONFIG.C0.DDR4_Mem_Add_Map                         {ROW_COLUMN_BANK_INTLV} \\
      CONFIG.C0.DDR4_MemoryName                          {MainMemory} \\
      CONFIG.C0.DDR4_MemoryPart                          {MTA18ASF2G72PZ-2G3} \\
      CONFIG.C0.DDR4_MemoryType                          {RDIMMs} \\
      CONFIG.C0.DDR4_MemoryVoltage                       {1.2V} \\
      CONFIG.C0.DDR4_OnDieTermination                    {RZQ/6} \\
      CONFIG.C0.DDR4_Ordering                            {Normal} \\
      CONFIG.C0.DDR4_OutputDriverImpedenceControl        {RZQ/7} \\
      CONFIG.C0.DDR4_PhyClockRatio                       {4:1} \\
      CONFIG.C0.DDR4_RESTORE_CRC                         {false} \\
      CONFIG.C0.DDR4_SAVE_RESTORE                        {false} \\
      CONFIG.C0.DDR4_SELF_REFRESH                        {false} \\
      CONFIG.C0.DDR4_Slot                                {Single} \\
      CONFIG.C0.DDR4_Specify_MandD                       {false} \\
      CONFIG.C0.DDR4_TREFI                               {0} \\
      CONFIG.C0.DDR4_TRFC                                {0} \\
      CONFIG.C0.DDR4_TRFC_DLR                            {0} \\
      CONFIG.C0.DDR4_TXPR                                {0} \\
      CONFIG.C0.DDR4_TimePeriod                          {833} \\
      CONFIG.C0.DDR4_UserRefresh_ZQCS                    {false} \\
      CONFIG.C0.DDR4_isCKEShared                         {false} \\
      CONFIG.C0.DDR4_isCustom                            {false} \\
      CONFIG.C0.DDR4_nCK_TREFI                           {0} \\
      CONFIG.C0.DDR4_nCK_TRFC                            {0} \\
      CONFIG.C0.DDR4_nCK_TRFC_DLR                        {0} \\
      CONFIG.C0.DDR4_nCK_TXPR                            {5} \\
      CONFIG.C0.LR_WIDTH                                 {1} \\
      CONFIG.C0.MIGRATION                                {false} \\
      CONFIG.C0.ODT_WIDTH                                {1} \\
      CONFIG.C0.StackHeight                              {1} \\
      CONFIG.C0_CLOCK_BOARD_INTERFACE                    {default_300mhz_clk0} \\
      CONFIG.C0_DDR4_BOARD_INTERFACE                     {ddr4_sdram_c0} \\
      CONFIG.DCI_Cascade                                 {false} \\
      CONFIG.DIFF_TERM_SYSCLK                            {false} \\
      CONFIG.Debug_Signal                                {Disable} \\
      CONFIG.Default_Bank_Selections                     {false} \\
      CONFIG.EN_PP_4R_MIR                                {false} \\
      CONFIG.Enable_SysPorts                             {true} \\
      CONFIG.Example_TG                                  {SIMPLE_TG} \\
      CONFIG.IOPowerReduction                            {OFF} \\
      CONFIG.IO_Power_Reduction                          {false} \\
      CONFIG.IS_FROM_PHY                                 {1} \\
      CONFIG.MCS_DBG_EN                                  {false} \\
      CONFIG.No_Controller                               {1} \\
      CONFIG.PARTIAL_RECONFIG_FLOW_MIG                   {false} \\
      CONFIG.PING_PONG_PHY                               {1} \\
      CONFIG.Phy_Only                                    {Complete_Memory_Controller} \\
      CONFIG.RECONFIG_XSDB_SAVE_RESTORE                  {false} \\
      CONFIG.RESET_BOARD_INTERFACE                       {Custom} \\
      CONFIG.Reference_Clock                             {Differential} \\
      CONFIG.SET_DW_TO_40                                {false} \\
      CONFIG.Simulation_Mode                             {BFM} \\
      CONFIG.System_Clock                                {Differential} \\
      CONFIG.TIMING_3DS                                  {false} \\
      CONFIG.TIMING_OP1                                  {false} \\
      CONFIG.TIMING_OP2                                  {false} \\
      ] [get_ips u250mig]""",
    fileSuffix = ".mig.vivado.tcl"
  )
}


class U250MIGIsland(c: HostMemChannelParams)(implicit p: Parameters) extends LazyModule with CrossesToOnlyOneClockDomain {
  val crossing = AsynchronousCrossing()
  val device = new MemoryDevice
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
      address       = Seq(AddressSet(0, c.size - 1)),
      resources     = device.reg,
      regionType    = RegionType.UNCACHED,
      executable    = true,
      supportsWrite = TransferSizes(1, c.maxXferBytes),
      supportsRead  = TransferSizes(1, c.maxXferBytes))),
    beatBytes = c.beatBytes)))

  lazy val module = new LazyModuleImp(this) {
    val ddrIF = IO(new Bundle with U250MIGIODDR)
    val clocksAndResets = IO(new Bundle with U250MIGIOClocksReset)

    //MIG black box instantiation
    val blackbox = Module(new U250MIGBlackBoxIP(c.beatBytes * 8, c.idBits))
    val (axi_async, _) = node.in(0)

    //pins to top level

    //inouts
    attach(ddrIF.c0_ddr4_dq,blackbox.io.c0_ddr4_dq)
    attach(ddrIF.c0_ddr4_dqs_c,blackbox.io.c0_ddr4_dqs_c)
    attach(ddrIF.c0_ddr4_dqs_t,blackbox.io.c0_ddr4_dqs_t)
    attach(ddrIF.c0_ddr4_dm_dbi_n,blackbox.io.c0_ddr4_dm_dbi_n)

    //outputs
    ddrIF.c0_ddr4_adr         := blackbox.io.c0_ddr4_adr
    ddrIF.c0_ddr4_bg          := blackbox.io.c0_ddr4_bg
    ddrIF.c0_ddr4_ba          := blackbox.io.c0_ddr4_ba
    ddrIF.c0_ddr4_reset_n     := blackbox.io.c0_ddr4_reset_n
    ddrIF.c0_ddr4_act_n       := blackbox.io.c0_ddr4_act_n
    ddrIF.c0_ddr4_ck_c        := blackbox.io.c0_ddr4_ck_c
    ddrIF.c0_ddr4_ck_t        := blackbox.io.c0_ddr4_ck_t
    ddrIF.c0_ddr4_cke         := blackbox.io.c0_ddr4_cke
    ddrIF.c0_ddr4_cs_n        := blackbox.io.c0_ddr4_cs_n
    ddrIF.c0_ddr4_odt         := blackbox.io.c0_ddr4_odt

    //inputs
    blackbox.io.c0_sys_clk_p    := clocksAndResets.c0_sys_clk_p
    blackbox.io.c0_sys_clk_n    := clocksAndResets.c0_sys_clk_n

    clocksAndResets.c0_ddr4_ui_clk          := blackbox.io.c0_ddr4_ui_clk
    clocksAndResets.c0_ddr4_ui_clk_sync_rst := blackbox.io.c0_ddr4_ui_clk_sync_rst
    blackbox.io.c0_ddr4_aresetn             := clocksAndResets.c0_ddr4_aresetn

    //slave AXI interface write address ports
    blackbox.io.c0_ddr4_s_axi_awid    := axi_async.aw.bits.id
    blackbox.io.c0_ddr4_s_axi_awaddr  := axi_async.aw.bits.addr
    blackbox.io.c0_ddr4_s_axi_awlen   := axi_async.aw.bits.len
    blackbox.io.c0_ddr4_s_axi_awsize  := axi_async.aw.bits.size
    blackbox.io.c0_ddr4_s_axi_awburst := axi_async.aw.bits.burst
    blackbox.io.c0_ddr4_s_axi_awlock  := axi_async.aw.bits.lock
    // Biancolin: i suspect that this (from FPGA shells) lets the upsizer pack narrow transactions.
    blackbox.io.c0_ddr4_s_axi_awcache := "b0011".U
    blackbox.io.c0_ddr4_s_axi_awprot  := axi_async.aw.bits.prot
    blackbox.io.c0_ddr4_s_axi_awqos   := axi_async.aw.bits.qos
    blackbox.io.c0_ddr4_s_axi_awvalid := axi_async.aw.valid
    axi_async.aw.ready        := blackbox.io.c0_ddr4_s_axi_awready

    //slave interface write data ports
    blackbox.io.c0_ddr4_s_axi_wdata   := axi_async.w.bits.data
    blackbox.io.c0_ddr4_s_axi_wstrb   := axi_async.w.bits.strb
    blackbox.io.c0_ddr4_s_axi_wlast   := axi_async.w.bits.last
    blackbox.io.c0_ddr4_s_axi_wvalid  := axi_async.w.valid
    axi_async.w.ready         := blackbox.io.c0_ddr4_s_axi_wready

    //slave interface write response
    blackbox.io.c0_ddr4_s_axi_bready  := axi_async.b.ready
    axi_async.b.bits.id       := blackbox.io.c0_ddr4_s_axi_bid
    axi_async.b.bits.resp     := blackbox.io.c0_ddr4_s_axi_bresp
    axi_async.b.valid         := blackbox.io.c0_ddr4_s_axi_bvalid

    //slave AXI interface read address ports
    blackbox.io.c0_ddr4_s_axi_arid    := axi_async.ar.bits.id
    blackbox.io.c0_ddr4_s_axi_araddr  := axi_async.ar.bits.addr
    blackbox.io.c0_ddr4_s_axi_arlen   := axi_async.ar.bits.len
    blackbox.io.c0_ddr4_s_axi_arsize  := axi_async.ar.bits.size
    blackbox.io.c0_ddr4_s_axi_arburst := axi_async.ar.bits.burst
    blackbox.io.c0_ddr4_s_axi_arlock  := axi_async.ar.bits.lock
    blackbox.io.c0_ddr4_s_axi_arcache := "b0011".U
    blackbox.io.c0_ddr4_s_axi_arprot  := axi_async.ar.bits.prot
    blackbox.io.c0_ddr4_s_axi_arqos   := axi_async.ar.bits.qos
    blackbox.io.c0_ddr4_s_axi_arvalid := axi_async.ar.valid
    axi_async.ar.ready        := blackbox.io.c0_ddr4_s_axi_arready

    //slave AXI interface read data ports
    blackbox.io.c0_ddr4_s_axi_rready  := axi_async.r.ready
    axi_async.r.bits.id       := blackbox.io.c0_ddr4_s_axi_rid
    axi_async.r.bits.data     := blackbox.io.c0_ddr4_s_axi_rdata
    axi_async.r.bits.resp     := blackbox.io.c0_ddr4_s_axi_rresp
    axi_async.r.bits.last     := blackbox.io.c0_ddr4_s_axi_rlast
    axi_async.r.valid         := blackbox.io.c0_ddr4_s_axi_rvalid

    //ECC control IF
    blackbox.io.c0_ddr4_s_axi_ctrl_awvalid := false.B
    blackbox.io.c0_ddr4_s_axi_ctrl_awaddr  := DontCare
    blackbox.io.c0_ddr4_s_axi_ctrl_wvalid  := false.B
    blackbox.io.c0_ddr4_s_axi_ctrl_wdata   := DontCare
    blackbox.io.c0_ddr4_s_axi_ctrl_bready  := true.B
    blackbox.io.c0_ddr4_s_axi_ctrl_arvalid := false.B
    blackbox.io.c0_ddr4_s_axi_ctrl_araddr  := DontCare
    blackbox.io.c0_ddr4_s_axi_ctrl_rready  := true.B

    //misc
    clocksAndResets.c0_init_calib_complete := blackbox.io.c0_init_calib_complete
    blackbox.io.sys_rst            := clocksAndResets.sys_rst
  }
}
