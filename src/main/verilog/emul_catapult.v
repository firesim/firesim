extern "A" void tick
(
  output reg                       reset,
  output reg                       fin,

  output reg [`PCIE_WIDTH-1:0]     pcie_in_bits,
  output reg                       pcie_in_valid,
  input  reg                       pcie_in_ready,

  input  reg [`PCIE_WIDTH-1:0]     pcie_out_bits,
  input  reg                       pcie_out_valid,
  output reg                       pcie_out_ready

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
  
  reg                        pcie_in_valid;
  wire                       pcie_in_ready;
  reg  [`PCIE_WIDTH-1:0]     pcie_in_bits;

  wire                       pcie_out_valid;
  reg                        pcie_out_ready;
  wire [`PCIE_WIDTH-1:0]     pcie_out_bits;

  wire                       pcie_in_valid_delay;
  wire                       pcie_in_ready_delay;
  wire [`PCIE_WIDTH-1:0]     pcie_in_bits_delay;

  wire                       pcie_out_valid_delay;
  wire                       pcie_out_ready_delay;
  wire [`PCIE_WIDTH-1:0]     pcie_out_bits_delay;

  assign #0.1 pcie_in_valid_delay = pcie_in_valid;
  assign #0.1 pcie_in_ready = pcie_in_ready_delay;
  assign #0.1 pcie_in_bits_delay = pcie_in_bits;

  assign #0.1 pcie_out_valid = pcie_out_valid_delay;
  assign #0.1 pcie_out_ready_delay = pcie_out_ready;
  assign #0.1 pcie_out_bits = pcie_out_bits_delay;

  CatapultShim CatapultShim(
    .clock(clock),
    .reset(reset),

    .io_pcie_in_valid(pcie_in_valid_delay),
    .io_pcie_in_ready(pcie_in_ready_delay),
    .io_pcie_in_bits(pcie_in_bits_delay),

    .io_pcie_out_valid(pcie_out_valid_delay),
    .io_pcie_out_ready(pcie_out_ready_delay),
    .io_pcie_out_bits(pcie_out_bits_delay)

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
      pcie_out_ready
    );
  end
endmodule;
