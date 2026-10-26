// See LICENSE for license details.
// Behavioral register slave; does not emulate the vendor debug protocol.
module slash_axi_debug_hub(
  input aclk,
  input aresetn,
  input s_axi_awid,
  input [20:0] s_axi_awaddr,
  input [7:0] s_axi_awlen,
  input [2:0] s_axi_awsize,
  input [1:0] s_axi_awburst,
  input s_axi_awlock,
  input [3:0] s_axi_awcache,
  input [2:0] s_axi_awprot,
  input [3:0] s_axi_awqos,
  input [3:0] s_axi_awregion,
  input s_axi_awvalid,
  output s_axi_awready,
  input s_axi_arid,
  input [20:0] s_axi_araddr,
  input [7:0] s_axi_arlen,
  input [2:0] s_axi_arsize,
  input [1:0] s_axi_arburst,
  input s_axi_arlock,
  input [3:0] s_axi_arcache,
  input [2:0] s_axi_arprot,
  input [3:0] s_axi_arqos,
  input [3:0] s_axi_arregion,
  input s_axi_arvalid,
  output s_axi_arready,
  input [31:0] s_axi_wdata,
  input [3:0] s_axi_wstrb,
  input s_axi_wlast,
  input s_axi_wvalid,
  output s_axi_wready,
  output s_axi_bid,
  output [1:0] s_axi_bresp,
  output s_axi_bvalid,
  input s_axi_bready,
  output s_axi_rid,
  output [31:0] s_axi_rdata,
  output [1:0] s_axi_rresp,
  output s_axi_rlast,
  output s_axi_rvalid,
  input s_axi_rready,
  input [31:0] s00_axis_tdata,
  input s00_axis_tvalid,
  input s00_axis_tlast,
  output s00_axis_tready,
  output [31:0] m00_axis_tdata,
  output m00_axis_tvalid,
  output m00_axis_tlast,
  input m00_axis_tready
);
  reg aw_pending, w_pending, write_id;
  reg read_valid, read_id;
  reg [31:0] read_data;
  assign s_axi_awready = !aw_pending;
  assign s_axi_wready = !w_pending;
  assign s_axi_bvalid = aw_pending && w_pending;
  assign s_axi_bid = write_id;
  assign s_axi_bresp = 0;
  assign s_axi_arready = !read_valid || s_axi_rready;
  assign s_axi_rvalid = read_valid;
  assign s_axi_rdata = read_data;
  assign s_axi_rid = read_id;
  assign s_axi_rresp = 0;
  assign s_axi_rlast = 1;
  assign s00_axis_tready = 1;
  assign m00_axis_tdata = 0;
  assign m00_axis_tvalid = 0;
  assign m00_axis_tlast = 0;
  always @(posedge aclk) begin
    if (!aresetn) begin
      aw_pending <= 0; w_pending <= 0; write_id <= 0;
      read_valid <= 0; read_id <= 0; read_data <= 0;
    end else begin
      if (s_axi_awvalid && s_axi_awready) begin
        aw_pending <= 1; write_id <= s_axi_awid;
        assert(s_axi_awlen == 0 && s_axi_awsize == 2);
      end
      if (s_axi_wvalid && s_axi_wready) begin
        w_pending <= 1; assert(s_axi_wlast);
      end
      if (s_axi_bvalid && s_axi_bready) begin aw_pending <= 0; w_pending <= 0; end
      if (s_axi_rvalid && s_axi_rready) read_valid <= 0;
      if (s_axi_arvalid && s_axi_arready) begin
        read_valid <= 1; read_id <= s_axi_arid;
        read_data <= 32'h534c4442 ^ {11'b0,s_axi_araddr};
        assert(s_axi_arlen == 0 && s_axi_arsize == 2);
      end
    end
  end
endmodule
