extern "A" void tick
(
  output reg                           reset,
  output reg                           fin,

  output reg [`PCIE_WIDTH-1:0]         pcie_in_bits,
  output reg                           pcie_in_valid,
  input  reg                           pcie_in_ready,

  input  reg [`PCIE_WIDTH-1:0]         pcie_out_bits,
  input  reg                           pcie_out_valid,
  output reg                           pcie_out_ready,

  output reg [`SOFTREG_ADDR_WIDTH-1:0] softreg_req_bits_addr,
  output reg [`SOFTREG_DATA_WIDTH-1:0] softreg_req_bits_wdata,
  output reg                           softreg_req_bits_wr,
  output reg                           softreg_req_valid,
  input  reg                           softreg_req_ready,

  input  reg [`SOFTREG_DATA_WIDTH-1:0] softreg_resp_bits_rdata,
  input  reg                           softreg_resp_valid,
  output reg                           softreg_resp_ready

  // TODO: UMI
);

module emul;
  reg clock = 1'b0;
  reg reset = 1'b1;
  reg fin = 1'b0;

  always #`CLOCK_PERIOD clock = ~clock;

  reg [1023:0] vcdplusfile = 0;

  initial begin
`ifdef DEBUG
    if ($value$plusargs("waveform=%s", vcdplusfile))
    begin
      $vcdplusfile(vcdplusfile);
      $vcdpluson(0);
      $vcdplusmemon(0);
    end
`endif
  end
  
  reg                             pcie_in_valid;
  wire                            pcie_in_ready;
  reg  [`PCIE_WIDTH-1:0]          pcie_in_bits;

  wire                            pcie_out_valid;
  reg                             pcie_out_ready;
  wire [`PCIE_WIDTH-1:0]          pcie_out_bits;

  reg                             softreg_req_valid;
  wire                            softreg_req_ready;
  reg  [`SOFTREG_ADDR_WIDTH-1:0]  softreg_req_bits_addr;
  reg  [`SOFTREG_DATA_WIDTH-1:0]  softreg_req_bits_wdata;
  reg                             softreg_req_bits_wr;

  wire [`SOFTREG_DATA_WIDTH-1:0]  softreg_resp_bits_rdata;
  wire                            softreg_resp_valid;
  reg                             softreg_resp_ready;

  wire                            pcie_in_valid_delay;
  wire                            pcie_in_ready_delay;
  wire [`PCIE_WIDTH-1:0]          pcie_in_bits_delay;

  wire                            pcie_out_valid_delay;
  wire                            pcie_out_ready_delay;
  wire [`PCIE_WIDTH-1:0]          pcie_out_bits_delay;

  wire [`SOFTREG_ADDR_WIDTH-1:0]  softreg_req_bits_addr_delay;
  wire [`SOFTREG_DATA_WIDTH-1:0]  softreg_req_bits_wdata_delay;
  wire                            softreg_req_bits_wr_delay;
  wire                            softreg_req_ready_delay;
  wire                            softreg_req_valid_delay;

  wire [`SOFTREG_DATA_WIDTH-1:0]  softreg_resp_bits_rdata_delay;
  wire                            softreg_resp_valid_delay;
  wire                            softreg_resp_ready_delay;

  assign #0.1 pcie_in_valid_delay = pcie_in_valid;
  assign #0.1 pcie_in_ready = pcie_in_ready_delay;
  assign #0.1 pcie_in_bits_delay = pcie_in_bits;

  assign #0.1 pcie_out_valid = pcie_out_valid_delay;
  assign #0.1 pcie_out_ready_delay = pcie_out_ready;
  assign #0.1 pcie_out_bits = pcie_out_bits_delay;

  assign #0.1 softreg_req_valid_delay = softreg_req_valid;
  assign #0.1 softreg_req_ready = softreg_req_ready_delay;
  assign #0.1 softreg_req_bits_addr_delay = softreg_req_bits_addr;
  assign #0.1 softreg_req_bits_wdata_delay = softreg_req_bits_wdata;
  assign #0.1 softreg_req_bits_wr_delay = softreg_req_bits_wr;

  assign #0.1 softreg_resp_valid = softreg_resp_valid_delay;
  assign #0.1 softreg_resp_ready_delay = softreg_resp_ready;
  assign #0.1 softreg_resp_bits_rdata = softreg_resp_bits_rdata_delay;

  CatapultShim CatapultShim(
    .clock(clock),
    .reset(reset),

    .io_pcie_in_valid(pcie_in_valid_delay),
    .io_pcie_in_ready(pcie_in_ready_delay),
    .io_pcie_in_bits(pcie_in_bits_delay),

    .io_pcie_out_valid(pcie_out_valid_delay),
    .io_pcie_out_ready(pcie_out_ready_delay),
    .io_pcie_out_bits(pcie_out_bits_delay),

    .io_softreg_req_valid(softreg_req_valid_delay),
    .io_softreg_req_ready(softreg_req_ready_delay),
    .io_softreg_req_bits_addr(softreg_req_bits_addr_delay),
    .io_softreg_req_bits_wdata(softreg_req_bits_wdata_delay),
    .io_softreg_req_bits_wr(softreg_req_bits_wr_delay),

    .io_softreg_resp_valid(softreg_resp_valid_delay),
    .io_softreg_resp_ready(softreg_resp_ready_delay),
    .io_softreg_resp_bits_rdata(softreg_resp_bits_rdata_delay)
    // TODO: UMI
  );

  always @(posedge clock) begin
    if (fin) begin
`ifdef DEBUG
      $vcdplusclose;
`endif
    end
    tick(
      reset,
      fin,

      pcie_in_bits,
      pcie_in_valid,
      pcie_in_ready,

      pcie_out_bits,
      pcie_out_valid,
      pcie_out_ready,

      softreg_req_bits_addr,
      softreg_req_bits_wdata,
      softreg_req_bits_wr,
      softreg_req_valid,
      softreg_req_ready,

      softreg_resp_bits_rdata,
      softreg_resp_valid,
      softreg_resp_ready
    );
  end
endmodule;
