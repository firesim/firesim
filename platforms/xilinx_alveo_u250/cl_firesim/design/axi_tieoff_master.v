`timescale 1ns/1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 09/10/2021 06:05:42 PM
// Design Name: 
// Module Name: axi_tieoff_master
// Project Name: 
// Target Devices: 
// Tool Versions: 
// Description: 
// 
// Dependencies: 
// 
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
// 
//////////////////////////////////////////////////////////////////////////////////


module axi_tieoff_master(
    TIEOFF_M_AXI_CTRL_0_araddr,
    TIEOFF_M_AXI_CTRL_0_arready,
    TIEOFF_M_AXI_CTRL_0_arvalid,
    TIEOFF_M_AXI_CTRL_0_awaddr,
    TIEOFF_M_AXI_CTRL_0_awready,
    TIEOFF_M_AXI_CTRL_0_awvalid,
    TIEOFF_M_AXI_CTRL_0_bready,
    TIEOFF_M_AXI_CTRL_0_bresp,
    TIEOFF_M_AXI_CTRL_0_bvalid,
    TIEOFF_M_AXI_CTRL_0_rdata,
    TIEOFF_M_AXI_CTRL_0_rready,
    TIEOFF_M_AXI_CTRL_0_rresp,
    TIEOFF_M_AXI_CTRL_0_rvalid,
    TIEOFF_M_AXI_CTRL_0_wdata,
    TIEOFF_M_AXI_CTRL_0_wready,
    TIEOFF_M_AXI_CTRL_0_wvalid);
    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME TIEOFF_M_AXI_CTRL_0, ADDR_WIDTH 32, ARUSER_WIDTH 0, AWUSER_WIDTH 0, BUSER_WIDTH 0, DATA_WIDTH 32, HAS_BRESP 1, HAS_BURST 0, HAS_CACHE 0, HAS_LOCK 0, HAS_PROT 0, HAS_QOS 0, HAS_REGION 0, HAS_RRESP 1, HAS_WSTRB 0, ID_WIDTH 0, INSERT_VIP 0, MAX_BURST_LENGTH 1, NUM_READ_OUTSTANDING 1, NUM_READ_THREADS 1, NUM_WRITE_OUTSTANDING 1, NUM_WRITE_THREADS 1, FREQ_HZ 300000000, PHASE 0.0, PROTOCOL AXI4LITE, READ_WRITE_MODE READ_WRITE, RUSER_BITS_PER_BYTE 0, RUSER_WIDTH 0, SUPPORTS_NARROW_BURST 0, WUSER_BITS_PER_BYTE 0, WUSER_WIDTH 0" *)
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 ARADDR" *) output[31:0] TIEOFF_M_AXI_CTRL_0_araddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 ARREADY" *) input TIEOFF_M_AXI_CTRL_0_arready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 ARVALID" *) output TIEOFF_M_AXI_CTRL_0_arvalid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 AWADDR" *) output[31:0] TIEOFF_M_AXI_CTRL_0_awaddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 AWREADY" *) input TIEOFF_M_AXI_CTRL_0_awready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 AWVALID" *) output TIEOFF_M_AXI_CTRL_0_awvalid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 BREADY" *) output TIEOFF_M_AXI_CTRL_0_bready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 BRESP" *) input[1:0] TIEOFF_M_AXI_CTRL_0_bresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 BVALID" *) input TIEOFF_M_AXI_CTRL_0_bvalid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 RDATA" *) input[31:0] TIEOFF_M_AXI_CTRL_0_rdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 RREADY" *) output TIEOFF_M_AXI_CTRL_0_rready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 RRESP" *) input[1:0] TIEOFF_M_AXI_CTRL_0_rresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 RVALID" *) input TIEOFF_M_AXI_CTRL_0_rvalid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 WDATA" *) output[31:0] TIEOFF_M_AXI_CTRL_0_wdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 WREADY" *) input TIEOFF_M_AXI_CTRL_0_wready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 TIEOFF_M_AXI_CTRL_0 WVALID" *) output TIEOFF_M_AXI_CTRL_0_wvalid;

    assign TIEOFF_M_AXI_CTRL_0_araddr = 32'b0;
    assign TIEOFF_M_AXI_CTRL_0_arvalid = 1'b0;
    assign TIEOFF_M_AXI_CTRL_0_awaddr = 32'b0;
    assign TIEOFF_M_AXI_CTRL_0_awvalid = 1'b0;
    assign TIEOFF_M_AXI_CTRL_0_bready = 1'b0;
    assign TIEOFF_M_AXI_CTRL_0_rready = 1'b0;
    assign TIEOFF_M_AXI_CTRL_0_wdata = 32'b0;
    assign TIEOFF_M_AXI_CTRL_0_wvalid = 1'b0;
endmodule
