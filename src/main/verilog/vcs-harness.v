extern "A" void tick
(
  output reg                          reset,
  output reg                          exit,
  output reg [31:0]                   exitcode,

  output reg                          master_ar_valid,
  input  reg                          master_ar_ready,
  output reg [`CHANNEL_ADDR_BITS-1:0] master_ar_addr,
  output reg [`CHANNEL_ID_BITS-1:0]   master_ar_id,
  output reg [2:0]                    master_ar_size,
  output reg [7:0]                    master_ar_len,

  output reg                          master_aw_valid,
  input  reg                          master_aw_ready,
  output reg [`CHANNEL_ADDR_BITS-1:0] master_aw_addr,
  output reg [`CHANNEL_ID_BITS-1:0]   master_aw_id,
  output reg [2:0]                    master_aw_size,
  output reg [7:0]                    master_aw_len,

  output reg                          master_w_valid,
  input  reg                          master_w_ready,
  output reg [`CHANNEL_STRB_BITS-1:0] master_w_strb,
  output reg [`CHANNEL_DATA_BITS-1:0] master_w_data,
  output reg                          master_w_last,

  input  reg                          master_r_valid,
  output reg                          master_r_ready,
  input  reg [1:0]                    master_r_resp,
  input  reg [`CHANNEL_ID_BITS-1:0]   master_r_id,
  input  reg [`CHANNEL_DATA_BITS-1:0] master_r_data,
  input  reg                          master_r_last,

  input  reg                          master_b_valid,
  output reg                          master_b_ready,
  input  reg [1:0]                    master_b_resp,
  input  reg [`CHANNEL_ID_BITS-1:0]   master_b_id,

  input  reg                          slave_ar_valid,
  output reg                          slave_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]     slave_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]       slave_ar_id,
  input  reg [2:0]                    slave_ar_size,
  input  reg [7:0]                    slave_ar_len,

  input  reg                          slave_aw_valid,
  output reg                          slave_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]     slave_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]       slave_aw_id,
  input  reg [2:0]                    slave_aw_size,
  input  reg [7:0]                    slave_aw_len,

  input  reg                          slave_w_valid,
  output reg                          slave_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]     slave_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]     slave_w_data,
  input  reg                          slave_w_last,

  output reg                          slave_r_valid,
  input  reg                          slave_r_ready,
  output reg [1:0]                    slave_r_resp,
  output reg [`MEM_ID_BITS-1:0]       slave_r_id,
  output reg [`MEM_DATA_BITS-1:0]     slave_r_data,
  output reg                          slave_r_last,

  output reg                          slave_b_valid,
  input  reg                          slave_b_ready,
  output reg [1:0]                    slave_b_resp,
  output reg [`MEM_ID_BITS-1:0]       slave_b_id
);

module vcsHarness;
  reg clock = 1'b0;
  reg reset = 1'b1;
  reg start = 1'b0;
  reg exit = 1'b0;
  reg [31:0] exitcode = 0;

  always #`CLOCK_PERIOD clock = ~clock;

  reg [1023:0] vcdplusfile = 0;
  reg [1023:0] vcdfile = 0;
  reg          verbose = 0;
  wire         printf_cond = verbose && !reset;
  integer      stderr = 32'h80000002;

  initial begin
    if ($value$plusargs("waveform=%s", vcdplusfile))
    begin
      $vcdplusfile(vcdplusfile);
      $vcdpluson(0);
      $vcdplusmemon(0);
    end
  end
  
  reg                           master_ar_valid;
  wire                          master_ar_ready;
  reg  [`CHANNEL_ADDR_BITS-1:0] master_ar_addr;
  reg  [`CHANNEL_ID_BITS-1:0]   master_ar_id;
  reg  [2:0]                    master_ar_size;
  reg  [7:0]                    master_ar_len;

  reg                           master_aw_valid;
  wire                          master_aw_ready;
  reg  [`CHANNEL_ADDR_BITS-1:0] master_aw_addr;
  reg  [`CHANNEL_ID_BITS-1:0]   master_aw_id;
  reg  [2:0]                    master_aw_size;
  reg  [7:0]                    master_aw_len;

  reg                           master_w_valid;
  wire                          master_w_ready;
  reg  [`CHANNEL_STRB_BITS-1:0] master_w_strb;
  reg  [`CHANNEL_DATA_BITS-1:0] master_w_data;
  reg                           master_w_last;

  wire                          master_r_valid;
  reg                           master_r_ready;
  wire [1:0]                    master_r_resp;
  wire [`CHANNEL_ID_BITS-1:0]   master_r_id;
  wire [`CHANNEL_DATA_BITS-1:0] master_r_data;
  wire                          master_r_last;

  wire                          master_b_valid;
  reg                           master_b_ready;
  wire [1:0]                    master_b_resp;
  wire [`CHANNEL_ID_BITS-1:0]   master_b_id;

  wire                          slave_ar_valid;
  reg                           slave_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]     slave_ar_addr;
  wire [`MEM_ID_BITS-1:0]       slave_ar_id;
  wire [2:0]                    slave_ar_size;
  wire [7:0]                    slave_ar_len;

  wire                          slave_aw_valid;
  reg                           slave_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]     slave_aw_addr;
  wire [`MEM_ID_BITS-1:0]       slave_aw_id;
  wire [2:0]                    slave_aw_size;
  wire [7:0]                    slave_aw_len;

  wire                          slave_w_valid;
  reg                           slave_w_ready;
  wire [`MEM_STRB_BITS-1:0]     slave_w_strb;
  wire [`MEM_DATA_BITS-1:0]     slave_w_data;
  wire                          slave_w_last;

  reg                           slave_r_valid;
  wire                          slave_r_ready;
  reg  [1:0]                    slave_r_resp;
  reg  [`MEM_ID_BITS-1:0]       slave_r_id;
  reg  [`MEM_DATA_BITS-1:0]     slave_r_data;
  reg                           slave_r_last;

  reg                           slave_b_valid;
  wire                          slave_b_ready;
  reg  [1:0]                    slave_b_resp;
  reg  [`MEM_ID_BITS-1:0]       slave_b_id;

  wire                          master_ar_valid_delay;
  wire                          master_ar_ready_delay;
  wire [`CHANNEL_ADDR_BITS-1:0] master_ar_addr_delay;
  wire [`CHANNEL_ID_BITS-1:0]   master_ar_id_delay;
  wire [2:0]                    master_ar_size_delay;
  wire [7:0]                    master_ar_len_delay;

  wire                          master_aw_valid_delay;
  wire                          master_aw_ready_delay;
  wire [`CHANNEL_ADDR_BITS-1:0] master_aw_addr_delay;
  wire [`CHANNEL_ID_BITS-1:0]   master_aw_id_delay;
  wire [2:0]                    master_aw_size_delay;
  wire [7:0]                    master_aw_len_delay;

  wire                          master_w_valid_delay;
  wire                          master_w_ready_delay;
  wire [`CHANNEL_STRB_BITS-1:0] master_w_strb_delay;
  wire [`CHANNEL_DATA_BITS-1:0] master_w_data_delay;
  wire                          master_w_last_delay;

  wire                          master_r_valid_delay;
  wire                          master_r_ready_delay;
  wire [1:0]                    master_r_resp_delay;
  wire [`CHANNEL_ID_BITS-1:0]   master_r_id_delay;
  wire [`CHANNEL_DATA_BITS-1:0] master_r_data_delay;
  wire                          master_r_last_delay;

  wire                          master_b_valid_delay;
  wire                          master_b_ready_delay;
  wire [1:0]                    master_b_resp_delay;
  wire [`CHANNEL_ID_BITS-1:0]   master_b_id_delay;

  wire                          slave_ar_valid_delay;
  wire                          slave_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]     slave_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]       slave_ar_id_delay;
  wire [2:0]                    slave_ar_size_delay;
  wire [7:0]                    slave_ar_len_delay;

  wire                          slave_aw_valid_delay;
  wire                          slave_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]     slave_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]       slave_aw_id_delay;
  wire [2:0]                    slave_aw_size_delay;
  wire [7:0]                    slave_aw_len_delay;

  wire                          slave_w_valid_delay;
  wire                          slave_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]     slave_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]     slave_w_data_delay;
  wire                          slave_w_last_delay;

  wire                          slave_r_valid_delay;
  wire                          slave_r_ready_delay;
  wire [1:0]                    slave_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]       slave_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]     slave_r_data_delay;
  wire                          slave_r_last_delay;

  wire                          slave_b_valid_delay;
  wire                          slave_b_ready_delay;
  wire [1:0]                    slave_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]       slave_b_id_delay;

  assign #0.1 master_ar_valid_delay = master_ar_valid;
  assign #0.1 master_ar_ready = master_ar_ready_delay;
  assign #0.1 master_ar_addr_delay = master_ar_addr;
  assign #0.1 master_ar_id_delay = master_ar_id;
  assign #0.1 master_ar_size_delay = master_ar_size;
  assign #0.1 master_ar_len_delay = master_ar_len;

  assign #0.1 master_aw_valid_delay = master_aw_valid;
  assign #0.1 master_aw_ready = master_aw_ready_delay;
  assign #0.1 master_aw_addr_delay = master_aw_addr;
  assign #0.1 master_aw_id_delay = master_aw_id;
  assign #0.1 master_aw_size_delay = master_aw_size;
  assign #0.1 master_aw_len_delay = master_aw_len;

  assign #0.1 master_w_valid_delay = master_w_valid;
  assign #0.1 master_w_ready = master_w_ready_delay;
  assign #0.1 master_w_strb_delay = master_w_strb;
  assign #0.1 master_w_data_delay = master_w_data;
  assign #0.1 master_w_last_delay = master_w_last;

  assign #0.1 master_r_valid = master_r_valid_delay;
  assign #0.1 master_r_ready_delay = master_r_ready;
  assign #0.1 master_r_resp = master_r_resp_delay;
  assign #0.1 master_r_id = master_r_id_delay;
  assign #0.1 master_r_data = master_r_data_delay;
  assign #0.1 master_r_last = master_r_last_delay;

  assign #0.1 master_b_valid = master_b_valid_delay;
  assign #0.1 master_b_ready_delay = master_b_ready;
  assign #0.1 master_b_resp = master_b_resp_delay;
  assign #0.1 master_b_id = master_b_id_delay;

  assign #0.1 slave_ar_valid = slave_ar_valid_delay;
  assign #0.1 slave_ar_ready_delay = slave_ar_ready;
  assign #0.1 slave_ar_addr = slave_ar_addr_delay;
  assign #0.1 slave_ar_id = slave_ar_id_delay;
  assign #0.1 slave_ar_size = slave_ar_size_delay;
  assign #0.1 slave_ar_len = slave_ar_len_delay;

  assign #0.1 slave_aw_valid = slave_aw_valid_delay;
  assign #0.1 slave_aw_ready_delay = slave_aw_ready;
  assign #0.1 slave_aw_addr = slave_aw_addr_delay;
  assign #0.1 slave_aw_id = slave_aw_id_delay;
  assign #0.1 slave_aw_size = slave_aw_size_delay;
  assign #0.1 slave_aw_len = slave_aw_len_delay;

  assign #0.1 slave_w_valid = slave_w_valid_delay;
  assign #0.1 slave_w_ready_delay = slave_w_ready;
  assign #0.1 slave_w_strb = slave_w_strb_delay;
  assign #0.1 slave_w_data = slave_w_data_delay;
  assign #0.1 slave_w_last = slave_w_last_delay;

  assign #0.1 slave_r_valid_delay = slave_r_valid;
  assign #0.1 slave_r_ready = slave_r_ready_delay;
  assign #0.1 slave_r_resp_delay = slave_r_resp;
  assign #0.1 slave_r_id_delay = slave_r_id;
  assign #0.1 slave_r_data_delay = slave_r_data;
  assign #0.1 slave_r_last_delay = slave_r_last;

  assign #0.1 slave_b_valid_delay = slave_b_valid;
  assign #0.1 slave_b_ready = slave_b_ready_delay;
  assign #0.1 slave_b_resp_delay = slave_b_resp;
  assign #0.1 slave_b_id_delay = slave_b_id;

  ZynqShim ZynqShim(
    .clock(clock),
    .reset(reset),

    .io_master_ar_valid(master_ar_valid_delay),
    .io_master_ar_ready(master_ar_ready_delay),
    .io_master_ar_bits_addr(master_ar_addr_delay),
    .io_master_ar_bits_id(master_ar_id_delay),
    .io_master_ar_bits_size(master_ar_size_delay),
    .io_master_ar_bits_len(master_ar_len_delay),

    .io_master_aw_valid(master_aw_valid_delay),
    .io_master_aw_ready(master_aw_ready_delay),
    .io_master_aw_bits_addr(master_aw_addr_delay),
    .io_master_aw_bits_id(master_aw_id_delay),
    .io_master_aw_bits_size(master_aw_size_delay),
    .io_master_aw_bits_len(master_aw_len_delay),

    .io_master_w_valid(master_w_valid_delay),
    .io_master_w_ready(master_w_ready_delay),
    .io_master_w_bits_strb(master_w_strb_delay),
    .io_master_w_bits_data(master_w_data_delay),
    .io_master_w_bits_last(master_w_last_delay),

    .io_master_r_valid(master_r_valid_delay),
    .io_master_r_ready(master_r_ready_delay),
    .io_master_r_bits_resp(master_r_resp_delay),
    .io_master_r_bits_id(master_r_id_delay),
    .io_master_r_bits_data(master_r_data_delay),
    .io_master_r_bits_last(master_r_last_delay),

    .io_master_b_valid(master_b_valid_delay),
    .io_master_b_ready(master_b_ready_delay),
    .io_master_b_bits_resp(master_b_resp_delay),
    .io_master_b_bits_id(master_b_id_delay),

    .io_slave_ar_valid(slave_ar_valid_delay),
    .io_slave_ar_ready(slave_ar_ready_delay),
    .io_slave_ar_bits_addr(slave_ar_addr_delay),
    .io_slave_ar_bits_id(slave_ar_id_delay),
    .io_slave_ar_bits_size(slave_ar_size_delay),
    .io_slave_ar_bits_len(slave_ar_len_delay),

    .io_slave_aw_valid(slave_aw_valid_delay),
    .io_slave_aw_ready(slave_aw_ready_delay),
    .io_slave_aw_bits_addr(slave_aw_addr_delay),
    .io_slave_aw_bits_id(slave_aw_id_delay),
    .io_slave_aw_bits_size(slave_aw_size_delay),
    .io_slave_aw_bits_len(slave_aw_len_delay),

    .io_slave_w_valid(slave_w_valid_delay),
    .io_slave_w_ready(slave_w_ready_delay),
    .io_slave_w_bits_strb(slave_w_strb_delay),
    .io_slave_w_bits_data(slave_w_data_delay),
    .io_slave_w_bits_last(slave_w_last_delay),

    .io_slave_r_valid(slave_r_valid_delay),
    .io_slave_r_ready(slave_r_ready_delay),
    .io_slave_r_bits_resp(slave_r_resp_delay),
    .io_slave_r_bits_id(slave_r_id_delay),
    .io_slave_r_bits_data(slave_r_data_delay),
    .io_slave_r_bits_last(slave_r_last_delay),

    .io_slave_b_valid(slave_b_valid_delay),
    .io_slave_b_ready(slave_b_ready_delay),
    .io_slave_b_bits_resp(slave_b_resp_delay),
    .io_slave_b_bits_id(slave_b_id_delay)
  );

  always @(posedge clock) begin
    if (exit) begin
      $vcdplusclose;
      if (exitcode == 0)
        $finish;
      else
        $fatal;
    end else tick(
      reset,
      exit,
      exitcode,

      master_ar_valid,
      master_ar_ready,
      master_ar_addr,
      master_ar_id,
      master_ar_size,
      master_ar_len,

      master_aw_valid,
      master_aw_ready,
      master_aw_addr,
      master_aw_id,
      master_aw_size,
      master_aw_len,

      master_w_valid,
      master_w_ready,
      master_w_strb,
      master_w_data,
      master_w_last,

      master_r_valid,
      master_r_ready,
      master_r_resp,
      master_r_id,
      master_r_data,
      master_r_last,

      master_b_valid,
      master_b_ready,
      master_b_resp,
      master_b_id,

      slave_ar_valid,
      slave_ar_ready,
      slave_ar_addr,
      slave_ar_id,
      slave_ar_size,
      slave_ar_len,

      slave_aw_valid,
      slave_aw_ready,
      slave_aw_addr,
      slave_aw_id,
      slave_aw_size,
      slave_aw_len,

      slave_w_valid,
      slave_w_ready,
      slave_w_strb,
      slave_w_data,
      slave_w_last,

      slave_r_valid,
      slave_r_ready,
      slave_r_resp,
      slave_r_id,
      slave_r_data,
      slave_r_last,

      slave_b_valid,
      slave_b_ready,
      slave_b_resp,
      slave_b_id
    );
  end
endmodule;
