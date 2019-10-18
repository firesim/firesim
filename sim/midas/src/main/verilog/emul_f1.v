extern "A" void tick
(
  output reg                       reset,
  output reg                       fin,

  output reg                       master_ar_valid,
  input  reg                       master_ar_ready,
  output reg [`CTRL_ADDR_BITS-1:0] master_ar_addr,
  output reg [`CTRL_ID_BITS-1:0]   master_ar_id,
  output reg [2:0]                 master_ar_size,
  output reg [7:0]                 master_ar_len,

  output reg                       master_aw_valid,
  input  reg                       master_aw_ready,
  output reg [`CTRL_ADDR_BITS-1:0] master_aw_addr,
  output reg [`CTRL_ID_BITS-1:0]   master_aw_id,
  output reg [2:0]                 master_aw_size,
  output reg [7:0]                 master_aw_len,

  output reg                       master_w_valid,
  input  reg                       master_w_ready,
  output reg [`CTRL_STRB_BITS-1:0] master_w_strb,
  output reg [`CTRL_DATA_BITS-1:0] master_w_data,
  output reg                       master_w_last,

  input  reg                       master_r_valid,
  output reg                       master_r_ready,
  input  reg [1:0]                 master_r_resp,
  input  reg [`CTRL_ID_BITS-1:0]   master_r_id,
  input  reg [`CTRL_DATA_BITS-1:0] master_r_data,
  input  reg                       master_r_last,

  input  reg                       master_b_valid,
  output reg                       master_b_ready,
  input  reg [1:0]                 master_b_resp,
  input  reg [`CTRL_ID_BITS-1:0]   master_b_id,

  output reg                       dma_ar_valid,
  input  reg                       dma_ar_ready,
  output reg [`DMA_ADDR_BITS-1:0]  dma_ar_addr,
  output reg [`DMA_ID_BITS-1:0]    dma_ar_id,
  output reg [2:0]                 dma_ar_size,
  output reg [7:0]                 dma_ar_len,

  output reg                       dma_aw_valid,
  input  reg                       dma_aw_ready,
  output reg [`DMA_ADDR_BITS-1:0]  dma_aw_addr,
  output reg [`DMA_ID_BITS-1:0]    dma_aw_id,
  output reg [2:0]                 dma_aw_size,
  output reg [7:0]                 dma_aw_len,

  output reg                       dma_w_valid,
  input  reg                       dma_w_ready,
  output reg [`DMA_STRB_BITS-1:0]  dma_w_strb,
  output reg [`DMA_DATA_BITS-1:0]  dma_w_data,
  output reg                       dma_w_last,

  input  reg                       dma_r_valid,
  output reg                       dma_r_ready,
  input  reg [1:0]                 dma_r_resp,
  input  reg [`DMA_ID_BITS-1:0]    dma_r_id,
  input  reg [`DMA_DATA_BITS-1:0]  dma_r_data,
  input  reg                       dma_r_last,

  input  reg                       dma_b_valid,
  output reg                       dma_b_ready,
  input  reg [1:0]                 dma_b_resp,
  input  reg [`DMA_ID_BITS-1:0]    dma_b_id,

  input  reg                       slave_0_ar_valid,
  output reg                       slave_0_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  slave_0_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]    slave_0_ar_id,
  input  reg [2:0]                 slave_0_ar_size,
  input  reg [7:0]                 slave_0_ar_len,

  input  reg                       slave_0_aw_valid,
  output reg                       slave_0_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  slave_0_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]    slave_0_aw_id,
  input  reg [2:0]                 slave_0_aw_size,
  input  reg [7:0]                 slave_0_aw_len,

  input  reg                       slave_0_w_valid,
  output reg                       slave_0_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]  slave_0_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]  slave_0_w_data,
  input  reg                       slave_0_w_last,

  output reg                       slave_0_r_valid,
  input  reg                       slave_0_r_ready,
  output reg [1:0]                 slave_0_r_resp,
  output reg [`MEM_ID_BITS-1:0]    slave_0_r_id,
  output reg [`MEM_DATA_BITS-1:0]  slave_0_r_data,
  output reg                       slave_0_r_last,

  output reg                       slave_0_b_valid,
  input  reg                       slave_0_b_ready,
  output reg [1:0]                 slave_0_b_resp,
  output reg [`MEM_ID_BITS-1:0]    slave_0_b_id,

  input  reg                       slave_1_ar_valid,
  output reg                       slave_1_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  slave_1_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]    slave_1_ar_id,
  input  reg [2:0]                 slave_1_ar_size,
  input  reg [7:0]                 slave_1_ar_len,

  input  reg                       slave_1_aw_valid,
  output reg                       slave_1_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  slave_1_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]    slave_1_aw_id,
  input  reg [2:0]                 slave_1_aw_size,
  input  reg [7:0]                 slave_1_aw_len,

  input  reg                       slave_1_w_valid,
  output reg                       slave_1_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]  slave_1_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]  slave_1_w_data,
  input  reg                       slave_1_w_last,

  output reg                       slave_1_r_valid,
  input  reg                       slave_1_r_ready,
  output reg [1:0]                 slave_1_r_resp,
  output reg [`MEM_ID_BITS-1:0]    slave_1_r_id,
  output reg [`MEM_DATA_BITS-1:0]  slave_1_r_data,
  output reg                       slave_1_r_last,

  output reg                       slave_1_b_valid,
  input  reg                       slave_1_b_ready,
  output reg [1:0]                 slave_1_b_resp,
  output reg [`MEM_ID_BITS-1:0]    slave_1_b_id,

  input  reg                       slave_2_ar_valid,
  output reg                       slave_2_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  slave_2_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]    slave_2_ar_id,
  input  reg [2:0]                 slave_2_ar_size,
  input  reg [7:0]                 slave_2_ar_len,

  input  reg                       slave_2_aw_valid,
  output reg                       slave_2_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  slave_2_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]    slave_2_aw_id,
  input  reg [2:0]                 slave_2_aw_size,
  input  reg [7:0]                 slave_2_aw_len,

  input  reg                       slave_2_w_valid,
  output reg                       slave_2_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]  slave_2_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]  slave_2_w_data,
  input  reg                       slave_2_w_last,

  output reg                       slave_2_r_valid,
  input  reg                       slave_2_r_ready,
  output reg [1:0]                 slave_2_r_resp,
  output reg [`MEM_ID_BITS-1:0]    slave_2_r_id,
  output reg [`MEM_DATA_BITS-1:0]  slave_2_r_data,
  output reg                       slave_2_r_last,

  output reg                       slave_2_b_valid,
  input  reg                       slave_2_b_ready,
  output reg [1:0]                 slave_2_b_resp,
  output reg [`MEM_ID_BITS-1:0]    slave_2_b_id,

  input  reg                       slave_3_ar_valid,
  output reg                       slave_3_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  slave_3_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]    slave_3_ar_id,
  input  reg [2:0]                 slave_3_ar_size,
  input  reg [7:0]                 slave_3_ar_len,

  input  reg                       slave_3_aw_valid,
  output reg                       slave_3_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  slave_3_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]    slave_3_aw_id,
  input  reg [2:0]                 slave_3_aw_size,
  input  reg [7:0]                 slave_3_aw_len,

  input  reg                       slave_3_w_valid,
  output reg                       slave_3_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]  slave_3_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]  slave_3_w_data,
  input  reg                       slave_3_w_last,

  output reg                       slave_3_r_valid,
  input  reg                       slave_3_r_ready,
  output reg [1:0]                 slave_3_r_resp,
  output reg [`MEM_ID_BITS-1:0]    slave_3_r_id,
  output reg [`MEM_DATA_BITS-1:0]  slave_3_r_data,
  output reg                       slave_3_r_last,

  output reg                       slave_3_b_valid,
  input  reg                       slave_3_b_ready,
  output reg [1:0]                 slave_3_b_resp,
  output reg [`MEM_ID_BITS-1:0]    slave_3_b_id
);

module emul;
  reg clock = 1'b0;
  reg reset = 1'b1;
  reg fin = 1'b0;

  always #(`CLOCK_PERIOD / 2.0) clock = ~clock;

  reg [2047:0] vcdplusfile = 0;
  reg [63:0] dump_start = 0;
  reg [63:0] dump_end = {64{1'b1}};
  reg [63:0] dump_cycles = 0;
  reg [63:0] trace_count = 0;

  initial begin
`ifdef DEBUG
    if ($value$plusargs("waveform=%s", vcdplusfile))
    begin
      $value$plusargs("dump-start=%d", dump_start);
      if ($value$plusargs("dump-cycles=%d", dump_cycles)) begin
        dump_end = dump_start + dump_cycles;
      end

      $vcdplusfile(vcdplusfile);
      wait (trace_count >= dump_start) begin
        $vcdpluson(0);
        $vcdplusmemon(0);
      end
      wait ((trace_count > dump_end) || fin) begin
        $vcdplusclose;
      end
    end
`endif
  end

  reg                        master_ar_valid;
  wire                       master_ar_ready;
  reg  [`CTRL_ADDR_BITS-1:0] master_ar_addr;
  reg  [`CTRL_ID_BITS-1:0]   master_ar_id;
  reg  [2:0]                 master_ar_size;
  reg  [7:0]                 master_ar_len;

  reg                        master_aw_valid;
  wire                       master_aw_ready;
  reg  [`CTRL_ADDR_BITS-1:0] master_aw_addr;
  reg  [`CTRL_ID_BITS-1:0]   master_aw_id;
  reg  [2:0]                 master_aw_size;
  reg  [7:0]                 master_aw_len;

  reg                        master_w_valid;
  wire                       master_w_ready;
  reg  [`CTRL_STRB_BITS-1:0] master_w_strb;
  reg  [`CTRL_DATA_BITS-1:0] master_w_data;
  reg                        master_w_last;

  wire                       master_r_valid;
  reg                        master_r_ready;
  wire [1:0]                 master_r_resp;
  wire [`CTRL_ID_BITS-1:0]   master_r_id;
  wire [`CTRL_DATA_BITS-1:0] master_r_data;
  wire                       master_r_last;

  wire                       master_b_valid;
  reg                        master_b_ready;
  wire [1:0]                 master_b_resp;
  wire [`CTRL_ID_BITS-1:0]   master_b_id;

  reg                        dma_ar_valid;
  wire                       dma_ar_ready;
  reg  [`DMA_ADDR_BITS-1:0]  dma_ar_addr;
  reg  [`DMA_ID_BITS-1:0]    dma_ar_id;
  reg  [2:0]                 dma_ar_size;
  reg  [7:0]                 dma_ar_len;

  reg                        dma_aw_valid;
  wire                       dma_aw_ready;
  reg  [`DMA_ADDR_BITS-1:0]  dma_aw_addr;
  reg  [`DMA_ID_BITS-1:0]    dma_aw_id;
  reg  [2:0]                 dma_aw_size;
  reg  [7:0]                 dma_aw_len;

  reg                        dma_w_valid;
  wire                       dma_w_ready;
  reg  [`DMA_STRB_BITS-1:0]  dma_w_strb;
  reg  [`DMA_DATA_BITS-1:0]  dma_w_data;
  reg                        dma_w_last;

  wire                       dma_r_valid;
  reg                        dma_r_ready;
  wire [1:0]                 dma_r_resp;
  wire [`DMA_ID_BITS-1:0]    dma_r_id;
  wire [`DMA_DATA_BITS-1:0]  dma_r_data;
  wire                       dma_r_last;

  wire                       dma_b_valid;
  reg                        dma_b_ready;
  wire [1:0]                 dma_b_resp;
  wire [`DMA_ID_BITS-1:0]    dma_b_id;

  wire                       slave_0_ar_valid;
  reg                        slave_0_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]  slave_0_ar_addr;
  wire [`MEM_ID_BITS-1:0]    slave_0_ar_id;
  wire [2:0]                 slave_0_ar_size;
  wire [7:0]                 slave_0_ar_len;

  wire                       slave_0_aw_valid;
  reg                        slave_0_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]  slave_0_aw_addr;
  wire [`MEM_ID_BITS-1:0]    slave_0_aw_id;
  wire [2:0]                 slave_0_aw_size;
  wire [7:0]                 slave_0_aw_len;

  wire                       slave_0_w_valid;
  reg                        slave_0_w_ready;
  wire [`MEM_STRB_BITS-1:0]  slave_0_w_strb;
  wire [`MEM_DATA_BITS-1:0]  slave_0_w_data;
  wire                       slave_0_w_last;

  reg                        slave_0_r_valid;
  wire                       slave_0_r_ready;
  reg  [1:0]                 slave_0_r_resp;
  reg  [`MEM_ID_BITS-1:0]    slave_0_r_id;
  reg  [`MEM_DATA_BITS-1:0]  slave_0_r_data;
  reg                        slave_0_r_last;

  reg                        slave_0_b_valid;
  wire                       slave_0_b_ready;
  reg  [1:0]                 slave_0_b_resp;
  reg  [`MEM_ID_BITS-1:0]    slave_0_b_id;

  wire                       slave_1_ar_valid;
  reg                        slave_1_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]  slave_1_ar_addr;
  wire [`MEM_ID_BITS-1:0]    slave_1_ar_id;
  wire [2:0]                 slave_1_ar_size;
  wire [7:0]                 slave_1_ar_len;

  wire                       slave_1_aw_valid;
  reg                        slave_1_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]  slave_1_aw_addr;
  wire [`MEM_ID_BITS-1:0]    slave_1_aw_id;
  wire [2:0]                 slave_1_aw_size;
  wire [7:0]                 slave_1_aw_len;

  wire                       slave_1_w_valid;
  reg                        slave_1_w_ready;
  wire [`MEM_STRB_BITS-1:0]  slave_1_w_strb;
  wire [`MEM_DATA_BITS-1:0]  slave_1_w_data;
  wire                       slave_1_w_last;

  reg                        slave_1_r_valid;
  wire                       slave_1_r_ready;
  reg  [1:0]                 slave_1_r_resp;
  reg  [`MEM_ID_BITS-1:0]    slave_1_r_id;
  reg  [`MEM_DATA_BITS-1:0]  slave_1_r_data;
  reg                        slave_1_r_last;

  reg                        slave_1_b_valid;
  wire                       slave_1_b_ready;
  reg  [1:0]                 slave_1_b_resp;
  reg  [`MEM_ID_BITS-1:0]    slave_1_b_id;

  wire                       slave_2_ar_valid;
  reg                        slave_2_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]  slave_2_ar_addr;
  wire [`MEM_ID_BITS-1:0]    slave_2_ar_id;
  wire [2:0]                 slave_2_ar_size;
  wire [7:0]                 slave_2_ar_len;

  wire                       slave_2_aw_valid;
  reg                        slave_2_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]  slave_2_aw_addr;
  wire [`MEM_ID_BITS-1:0]    slave_2_aw_id;
  wire [2:0]                 slave_2_aw_size;
  wire [7:0]                 slave_2_aw_len;

  wire                       slave_2_w_valid;
  reg                        slave_2_w_ready;
  wire [`MEM_STRB_BITS-1:0]  slave_2_w_strb;
  wire [`MEM_DATA_BITS-1:0]  slave_2_w_data;
  wire                       slave_2_w_last;

  reg                        slave_2_r_valid;
  wire                       slave_2_r_ready;
  reg  [1:0]                 slave_2_r_resp;
  reg  [`MEM_ID_BITS-1:0]    slave_2_r_id;
  reg  [`MEM_DATA_BITS-1:0]  slave_2_r_data;
  reg                        slave_2_r_last;

  reg                        slave_2_b_valid;
  wire                       slave_2_b_ready;
  reg  [1:0]                 slave_2_b_resp;
  reg  [`MEM_ID_BITS-1:0]    slave_2_b_id;

  wire                       slave_3_ar_valid;
  reg                        slave_3_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]  slave_3_ar_addr;
  wire [`MEM_ID_BITS-1:0]    slave_3_ar_id;
  wire [2:0]                 slave_3_ar_size;
  wire [7:0]                 slave_3_ar_len;

  wire                       slave_3_aw_valid;
  reg                        slave_3_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]  slave_3_aw_addr;
  wire [`MEM_ID_BITS-1:0]    slave_3_aw_id;
  wire [2:0]                 slave_3_aw_size;
  wire [7:0]                 slave_3_aw_len;

  wire                       slave_3_w_valid;
  reg                        slave_3_w_ready;
  wire [`MEM_STRB_BITS-1:0]  slave_3_w_strb;
  wire [`MEM_DATA_BITS-1:0]  slave_3_w_data;
  wire                       slave_3_w_last;

  reg                        slave_3_r_valid;
  wire                       slave_3_r_ready;
  reg  [1:0]                 slave_3_r_resp;
  reg  [`MEM_ID_BITS-1:0]    slave_3_r_id;
  reg  [`MEM_DATA_BITS-1:0]  slave_3_r_data;
  reg                        slave_3_r_last;

  reg                        slave_3_b_valid;
  wire                       slave_3_b_ready;
  reg  [1:0]                 slave_3_b_resp;
  reg  [`MEM_ID_BITS-1:0]    slave_3_b_id;

  wire                       master_ar_valid_delay;
  wire                       master_ar_ready_delay;
  wire [`CTRL_ADDR_BITS-1:0] master_ar_addr_delay;
  wire [`CTRL_ID_BITS-1:0]   master_ar_id_delay;
  wire [2:0]                 master_ar_size_delay;
  wire [7:0]                 master_ar_len_delay;

  wire                       master_aw_valid_delay;
  wire                       master_aw_ready_delay;
  wire [`CTRL_ADDR_BITS-1:0] master_aw_addr_delay;
  wire [`CTRL_ID_BITS-1:0]   master_aw_id_delay;
  wire [2:0]                 master_aw_size_delay;
  wire [7:0]                 master_aw_len_delay;

  wire                       master_w_valid_delay;
  wire                       master_w_ready_delay;
  wire [`CTRL_STRB_BITS-1:0] master_w_strb_delay;
  wire [`CTRL_DATA_BITS-1:0] master_w_data_delay;
  wire                       master_w_last_delay;

  wire                       master_r_valid_delay;
  wire                       master_r_ready_delay;
  wire [1:0]                 master_r_resp_delay;
  wire [`CTRL_ID_BITS-1:0]   master_r_id_delay;
  wire [`CTRL_DATA_BITS-1:0] master_r_data_delay;
  wire                       master_r_last_delay;

  wire                       master_b_valid_delay;
  wire                       master_b_ready_delay;
  wire [1:0]                 master_b_resp_delay;
  wire [`CTRL_ID_BITS-1:0]   master_b_id_delay;

  wire                       dma_ar_valid_delay;
  wire                       dma_ar_ready_delay;
  wire [`DMA_ADDR_BITS-1:0]  dma_ar_addr_delay;
  wire [`DMA_ID_BITS-1:0]    dma_ar_id_delay;
  wire [2:0]                 dma_ar_size_delay;
  wire [7:0]                 dma_ar_len_delay;

  wire                       dma_aw_valid_delay;
  wire                       dma_aw_ready_delay;
  wire [`DMA_ADDR_BITS-1:0]  dma_aw_addr_delay;
  wire [`DMA_ID_BITS-1:0]    dma_aw_id_delay;
  wire [2:0]                 dma_aw_size_delay;
  wire [7:0]                 dma_aw_len_delay;

  wire                       dma_w_valid_delay;
  wire                       dma_w_ready_delay;
  wire [`DMA_STRB_BITS-1:0]  dma_w_strb_delay;
  wire [`DMA_DATA_BITS-1:0]  dma_w_data_delay;
  wire                       dma_w_last_delay;

  wire                       dma_r_valid_delay;
  wire                       dma_r_ready_delay;
  wire [1:0]                 dma_r_resp_delay;
  wire [`DMA_ID_BITS-1:0]    dma_r_id_delay;
  wire [`DMA_DATA_BITS-1:0]  dma_r_data_delay;
  wire                       dma_r_last_delay;

  wire                       dma_b_valid_delay;
  wire                       dma_b_ready_delay;
  wire [1:0]                 dma_b_resp_delay;
  wire [`DMA_ID_BITS-1:0]    dma_b_id_delay;

  wire                       slave_0_ar_valid_delay;
  wire                       slave_0_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  slave_0_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]    slave_0_ar_id_delay;
  wire [2:0]                 slave_0_ar_size_delay;
  wire [7:0]                 slave_0_ar_len_delay;

  wire                       slave_0_aw_valid_delay;
  wire                       slave_0_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  slave_0_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]    slave_0_aw_id_delay;
  wire [2:0]                 slave_0_aw_size_delay;
  wire [7:0]                 slave_0_aw_len_delay;

  wire                       slave_0_w_valid_delay;
  wire                       slave_0_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]  slave_0_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]  slave_0_w_data_delay;
  wire                       slave_0_w_last_delay;

  wire                       slave_0_r_valid_delay;
  wire                       slave_0_r_ready_delay;
  wire [1:0]                 slave_0_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]    slave_0_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]  slave_0_r_data_delay;
  wire                       slave_0_r_last_delay;

  wire                       slave_0_b_valid_delay;
  wire                       slave_0_b_ready_delay;
  wire [1:0]                 slave_0_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]    slave_0_b_id_delay;

  wire                       slave_1_ar_valid_delay;
  wire                       slave_1_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  slave_1_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]    slave_1_ar_id_delay;
  wire [2:0]                 slave_1_ar_size_delay;
  wire [7:0]                 slave_1_ar_len_delay;

  wire                       slave_1_aw_valid_delay;
  wire                       slave_1_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  slave_1_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]    slave_1_aw_id_delay;
  wire [2:0]                 slave_1_aw_size_delay;
  wire [7:0]                 slave_1_aw_len_delay;

  wire                       slave_1_w_valid_delay;
  wire                       slave_1_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]  slave_1_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]  slave_1_w_data_delay;
  wire                       slave_1_w_last_delay;

  wire                       slave_1_r_valid_delay;
  wire                       slave_1_r_ready_delay;
  wire [1:0]                 slave_1_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]    slave_1_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]  slave_1_r_data_delay;
  wire                       slave_1_r_last_delay;

  wire                       slave_1_b_valid_delay;
  wire                       slave_1_b_ready_delay;
  wire [1:0]                 slave_1_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]    slave_1_b_id_delay;

  wire                       slave_2_ar_valid_delay;
  wire                       slave_2_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  slave_2_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]    slave_2_ar_id_delay;
  wire [2:0]                 slave_2_ar_size_delay;
  wire [7:0]                 slave_2_ar_len_delay;

  wire                       slave_2_aw_valid_delay;
  wire                       slave_2_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  slave_2_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]    slave_2_aw_id_delay;
  wire [2:0]                 slave_2_aw_size_delay;
  wire [7:0]                 slave_2_aw_len_delay;

  wire                       slave_2_w_valid_delay;
  wire                       slave_2_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]  slave_2_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]  slave_2_w_data_delay;
  wire                       slave_2_w_last_delay;

  wire                       slave_2_r_valid_delay;
  wire                       slave_2_r_ready_delay;
  wire [1:0]                 slave_2_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]    slave_2_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]  slave_2_r_data_delay;
  wire                       slave_2_r_last_delay;

  wire                       slave_2_b_valid_delay;
  wire                       slave_2_b_ready_delay;
  wire [1:0]                 slave_2_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]    slave_2_b_id_delay;

  wire                       slave_3_ar_valid_delay;
  wire                       slave_3_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  slave_3_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]    slave_3_ar_id_delay;
  wire [2:0]                 slave_3_ar_size_delay;
  wire [7:0]                 slave_3_ar_len_delay;

  wire                       slave_3_aw_valid_delay;
  wire                       slave_3_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  slave_3_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]    slave_3_aw_id_delay;
  wire [2:0]                 slave_3_aw_size_delay;
  wire [7:0]                 slave_3_aw_len_delay;

  wire                       slave_3_w_valid_delay;
  wire                       slave_3_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]  slave_3_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]  slave_3_w_data_delay;
  wire                       slave_3_w_last_delay;

  wire                       slave_3_r_valid_delay;
  wire                       slave_3_r_ready_delay;
  wire [1:0]                 slave_3_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]    slave_3_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]  slave_3_r_data_delay;
  wire                       slave_3_r_last_delay;

  wire                       slave_3_b_valid_delay;
  wire                       slave_3_b_ready_delay;
  wire [1:0]                 slave_3_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]    slave_3_b_id_delay;

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

  assign #0.1 dma_ar_valid_delay = dma_ar_valid;
  assign #0.1 dma_ar_ready = dma_ar_ready_delay;
  assign #0.1 dma_ar_addr_delay = dma_ar_addr;
  assign #0.1 dma_ar_id_delay = dma_ar_id;
  assign #0.1 dma_ar_size_delay = dma_ar_size;
  assign #0.1 dma_ar_len_delay = dma_ar_len;

  assign #0.1 dma_aw_valid_delay = dma_aw_valid;
  assign #0.1 dma_aw_ready = dma_aw_ready_delay;
  assign #0.1 dma_aw_addr_delay = dma_aw_addr;
  assign #0.1 dma_aw_id_delay = dma_aw_id;
  assign #0.1 dma_aw_size_delay = dma_aw_size;
  assign #0.1 dma_aw_len_delay = dma_aw_len;

  assign #0.1 dma_w_valid_delay = dma_w_valid;
  assign #0.1 dma_w_ready = dma_w_ready_delay;
  assign #0.1 dma_w_strb_delay = dma_w_strb;
  assign #0.1 dma_w_data_delay = dma_w_data;
  assign #0.1 dma_w_last_delay = dma_w_last;

  assign #0.1 dma_r_valid = dma_r_valid_delay;
  assign #0.1 dma_r_ready_delay = dma_r_ready;
  assign #0.1 dma_r_resp = dma_r_resp_delay;
  assign #0.1 dma_r_id = dma_r_id_delay;
  assign #0.1 dma_r_data = dma_r_data_delay;
  assign #0.1 dma_r_last = dma_r_last_delay;

  assign #0.1 dma_b_valid = dma_b_valid_delay;
  assign #0.1 dma_b_ready_delay = dma_b_ready;
  assign #0.1 dma_b_resp = dma_b_resp_delay;
  assign #0.1 dma_b_id = dma_b_id_delay;

  assign #0.1 slave_0_ar_valid = slave_0_ar_valid_delay;
  assign #0.1 slave_0_ar_ready_delay = slave_0_ar_ready;
  assign #0.1 slave_0_ar_addr = slave_0_ar_addr_delay;
  assign #0.1 slave_0_ar_id = slave_0_ar_id_delay;
  assign #0.1 slave_0_ar_size = slave_0_ar_size_delay;
  assign #0.1 slave_0_ar_len = slave_0_ar_len_delay;

  assign #0.1 slave_0_aw_valid = slave_0_aw_valid_delay;
  assign #0.1 slave_0_aw_ready_delay = slave_0_aw_ready;
  assign #0.1 slave_0_aw_addr = slave_0_aw_addr_delay;
  assign #0.1 slave_0_aw_id = slave_0_aw_id_delay;
  assign #0.1 slave_0_aw_size = slave_0_aw_size_delay;
  assign #0.1 slave_0_aw_len = slave_0_aw_len_delay;

  assign #0.1 slave_0_w_valid = slave_0_w_valid_delay;
  assign #0.1 slave_0_w_ready_delay = slave_0_w_ready;
  assign #0.1 slave_0_w_strb = slave_0_w_strb_delay;
  assign #0.1 slave_0_w_data = slave_0_w_data_delay;
  assign #0.1 slave_0_w_last = slave_0_w_last_delay;

  assign #0.1 slave_0_r_valid_delay = slave_0_r_valid;
  assign #0.1 slave_0_r_ready = slave_0_r_ready_delay;
  assign #0.1 slave_0_r_resp_delay = slave_0_r_resp;
  assign #0.1 slave_0_r_id_delay = slave_0_r_id;
  assign #0.1 slave_0_r_data_delay = slave_0_r_data;
  assign #0.1 slave_0_r_last_delay = slave_0_r_last;

  assign #0.1 slave_0_b_valid_delay = slave_0_b_valid;
  assign #0.1 slave_0_b_ready = slave_0_b_ready_delay;
  assign #0.1 slave_0_b_resp_delay = slave_0_b_resp;
  assign #0.1 slave_0_b_id_delay = slave_0_b_id;

  assign #0.1 slave_1_ar_valid = slave_1_ar_valid_delay;
  assign #0.1 slave_1_ar_ready_delay = slave_1_ar_ready;
  assign #0.1 slave_1_ar_addr = slave_1_ar_addr_delay;
  assign #0.1 slave_1_ar_id = slave_1_ar_id_delay;
  assign #0.1 slave_1_ar_size = slave_1_ar_size_delay;
  assign #0.1 slave_1_ar_len = slave_1_ar_len_delay;

  assign #0.1 slave_1_aw_valid = slave_1_aw_valid_delay;
  assign #0.1 slave_1_aw_ready_delay = slave_1_aw_ready;
  assign #0.1 slave_1_aw_addr = slave_1_aw_addr_delay;
  assign #0.1 slave_1_aw_id = slave_1_aw_id_delay;
  assign #0.1 slave_1_aw_size = slave_1_aw_size_delay;
  assign #0.1 slave_1_aw_len = slave_1_aw_len_delay;

  assign #0.1 slave_1_w_valid = slave_1_w_valid_delay;
  assign #0.1 slave_1_w_ready_delay = slave_1_w_ready;
  assign #0.1 slave_1_w_strb = slave_1_w_strb_delay;
  assign #0.1 slave_1_w_data = slave_1_w_data_delay;
  assign #0.1 slave_1_w_last = slave_1_w_last_delay;

  assign #0.1 slave_1_r_valid_delay = slave_1_r_valid;
  assign #0.1 slave_1_r_ready = slave_1_r_ready_delay;
  assign #0.1 slave_1_r_resp_delay = slave_1_r_resp;
  assign #0.1 slave_1_r_id_delay = slave_1_r_id;
  assign #0.1 slave_1_r_data_delay = slave_1_r_data;
  assign #0.1 slave_1_r_last_delay = slave_1_r_last;

  assign #0.1 slave_1_b_valid_delay = slave_1_b_valid;
  assign #0.1 slave_1_b_ready = slave_1_b_ready_delay;
  assign #0.1 slave_1_b_resp_delay = slave_1_b_resp;
  assign #0.1 slave_1_b_id_delay = slave_1_b_id;

  assign #0.1 slave_2_ar_valid = slave_2_ar_valid_delay;
  assign #0.1 slave_2_ar_ready_delay = slave_2_ar_ready;
  assign #0.1 slave_2_ar_addr = slave_2_ar_addr_delay;
  assign #0.1 slave_2_ar_id = slave_2_ar_id_delay;
  assign #0.1 slave_2_ar_size = slave_2_ar_size_delay;
  assign #0.1 slave_2_ar_len = slave_2_ar_len_delay;

  assign #0.1 slave_2_aw_valid = slave_2_aw_valid_delay;
  assign #0.1 slave_2_aw_ready_delay = slave_2_aw_ready;
  assign #0.1 slave_2_aw_addr = slave_2_aw_addr_delay;
  assign #0.1 slave_2_aw_id = slave_2_aw_id_delay;
  assign #0.1 slave_2_aw_size = slave_2_aw_size_delay;
  assign #0.1 slave_2_aw_len = slave_2_aw_len_delay;

  assign #0.1 slave_2_w_valid = slave_2_w_valid_delay;
  assign #0.1 slave_2_w_ready_delay = slave_2_w_ready;
  assign #0.1 slave_2_w_strb = slave_2_w_strb_delay;
  assign #0.1 slave_2_w_data = slave_2_w_data_delay;
  assign #0.1 slave_2_w_last = slave_2_w_last_delay;

  assign #0.1 slave_2_r_valid_delay = slave_2_r_valid;
  assign #0.1 slave_2_r_ready = slave_2_r_ready_delay;
  assign #0.1 slave_2_r_resp_delay = slave_2_r_resp;
  assign #0.1 slave_2_r_id_delay = slave_2_r_id;
  assign #0.1 slave_2_r_data_delay = slave_2_r_data;
  assign #0.1 slave_2_r_last_delay = slave_2_r_last;

  assign #0.1 slave_2_b_valid_delay = slave_2_b_valid;
  assign #0.1 slave_2_b_ready = slave_2_b_ready_delay;
  assign #0.1 slave_2_b_resp_delay = slave_2_b_resp;
  assign #0.1 slave_2_b_id_delay = slave_2_b_id;

  assign #0.1 slave_3_ar_valid = slave_3_ar_valid_delay;
  assign #0.1 slave_3_ar_ready_delay = slave_3_ar_ready;
  assign #0.1 slave_3_ar_addr = slave_3_ar_addr_delay;
  assign #0.1 slave_3_ar_id = slave_3_ar_id_delay;
  assign #0.1 slave_3_ar_size = slave_3_ar_size_delay;
  assign #0.1 slave_3_ar_len = slave_3_ar_len_delay;

  assign #0.1 slave_3_aw_valid = slave_3_aw_valid_delay;
  assign #0.1 slave_3_aw_ready_delay = slave_3_aw_ready;
  assign #0.1 slave_3_aw_addr = slave_3_aw_addr_delay;
  assign #0.1 slave_3_aw_id = slave_3_aw_id_delay;
  assign #0.1 slave_3_aw_size = slave_3_aw_size_delay;
  assign #0.1 slave_3_aw_len = slave_3_aw_len_delay;

  assign #0.1 slave_3_w_valid = slave_3_w_valid_delay;
  assign #0.1 slave_3_w_ready_delay = slave_3_w_ready;
  assign #0.1 slave_3_w_strb = slave_3_w_strb_delay;
  assign #0.1 slave_3_w_data = slave_3_w_data_delay;
  assign #0.1 slave_3_w_last = slave_3_w_last_delay;

  assign #0.1 slave_3_r_valid_delay = slave_3_r_valid;
  assign #0.1 slave_3_r_ready = slave_3_r_ready_delay;
  assign #0.1 slave_3_r_resp_delay = slave_3_r_resp;
  assign #0.1 slave_3_r_id_delay = slave_3_r_id;
  assign #0.1 slave_3_r_data_delay = slave_3_r_data;
  assign #0.1 slave_3_r_last_delay = slave_3_r_last;

  assign #0.1 slave_3_b_valid_delay = slave_3_b_valid;
  assign #0.1 slave_3_b_ready = slave_3_b_ready_delay;
  assign #0.1 slave_3_b_resp_delay = slave_3_b_resp;
  assign #0.1 slave_3_b_id_delay = slave_3_b_id;

  F1Shim F1Shim(
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

    .io_dma_ar_valid(dma_ar_valid_delay),
    .io_dma_ar_ready(dma_ar_ready_delay),
    .io_dma_ar_bits_addr(dma_ar_addr_delay),
    .io_dma_ar_bits_id(dma_ar_id_delay),
    .io_dma_ar_bits_size(dma_ar_size_delay),
    .io_dma_ar_bits_len(dma_ar_len_delay),

    .io_dma_aw_valid(dma_aw_valid_delay),
    .io_dma_aw_ready(dma_aw_ready_delay),
    .io_dma_aw_bits_addr(dma_aw_addr_delay),
    .io_dma_aw_bits_id(dma_aw_id_delay),
    .io_dma_aw_bits_size(dma_aw_size_delay),
    .io_dma_aw_bits_len(dma_aw_len_delay),

    .io_dma_w_valid(dma_w_valid_delay),
    .io_dma_w_ready(dma_w_ready_delay),
    .io_dma_w_bits_strb(dma_w_strb_delay),
    .io_dma_w_bits_data(dma_w_data_delay),
    .io_dma_w_bits_last(dma_w_last_delay),

    .io_dma_r_valid(dma_r_valid_delay),
    .io_dma_r_ready(dma_r_ready_delay),
    .io_dma_r_bits_resp(dma_r_resp_delay),
    .io_dma_r_bits_id(dma_r_id_delay),
    .io_dma_r_bits_data(dma_r_data_delay),
    .io_dma_r_bits_last(dma_r_last_delay),

    .io_dma_b_valid(dma_b_valid_delay),
    .io_dma_b_ready(dma_b_ready_delay),
    .io_dma_b_bits_resp(dma_b_resp_delay),
    .io_dma_b_bits_id(dma_b_id_delay),

    .io_slave_0_ar_valid(slave_0_ar_valid_delay),
    .io_slave_0_ar_ready(slave_0_ar_ready_delay),
    .io_slave_0_ar_bits_addr(slave_0_ar_addr_delay),
    .io_slave_0_ar_bits_id(slave_0_ar_id_delay),
    .io_slave_0_ar_bits_size(slave_0_ar_size_delay),
    .io_slave_0_ar_bits_len(slave_0_ar_len_delay),

    .io_slave_0_aw_valid(slave_0_aw_valid_delay),
    .io_slave_0_aw_ready(slave_0_aw_ready_delay),
    .io_slave_0_aw_bits_addr(slave_0_aw_addr_delay),
    .io_slave_0_aw_bits_id(slave_0_aw_id_delay),
    .io_slave_0_aw_bits_size(slave_0_aw_size_delay),
    .io_slave_0_aw_bits_len(slave_0_aw_len_delay),

    .io_slave_0_w_valid(slave_0_w_valid_delay),
    .io_slave_0_w_ready(slave_0_w_ready_delay),
    .io_slave_0_w_bits_strb(slave_0_w_strb_delay),
    .io_slave_0_w_bits_data(slave_0_w_data_delay),
    .io_slave_0_w_bits_last(slave_0_w_last_delay),

    .io_slave_0_r_valid(slave_0_r_valid_delay),
    .io_slave_0_r_ready(slave_0_r_ready_delay),
    .io_slave_0_r_bits_resp(slave_0_r_resp_delay),
    .io_slave_0_r_bits_id(slave_0_r_id_delay),
    .io_slave_0_r_bits_data(slave_0_r_data_delay),
    .io_slave_0_r_bits_last(slave_0_r_last_delay),

    .io_slave_0_b_valid(slave_0_b_valid_delay),
    .io_slave_0_b_ready(slave_0_b_ready_delay),
    .io_slave_0_b_bits_resp(slave_0_b_resp_delay),
    .io_slave_0_b_bits_id(slave_0_b_id_delay),

    .io_slave_1_ar_valid(slave_1_ar_valid_delay),
    .io_slave_1_ar_ready(slave_1_ar_ready_delay),
    .io_slave_1_ar_bits_addr(slave_1_ar_addr_delay),
    .io_slave_1_ar_bits_id(slave_1_ar_id_delay),
    .io_slave_1_ar_bits_size(slave_1_ar_size_delay),
    .io_slave_1_ar_bits_len(slave_1_ar_len_delay),

    .io_slave_1_aw_valid(slave_1_aw_valid_delay),
    .io_slave_1_aw_ready(slave_1_aw_ready_delay),
    .io_slave_1_aw_bits_addr(slave_1_aw_addr_delay),
    .io_slave_1_aw_bits_id(slave_1_aw_id_delay),
    .io_slave_1_aw_bits_size(slave_1_aw_size_delay),
    .io_slave_1_aw_bits_len(slave_1_aw_len_delay),

    .io_slave_1_w_valid(slave_1_w_valid_delay),
    .io_slave_1_w_ready(slave_1_w_ready_delay),
    .io_slave_1_w_bits_strb(slave_1_w_strb_delay),
    .io_slave_1_w_bits_data(slave_1_w_data_delay),
    .io_slave_1_w_bits_last(slave_1_w_last_delay),

    .io_slave_1_r_valid(slave_1_r_valid_delay),
    .io_slave_1_r_ready(slave_1_r_ready_delay),
    .io_slave_1_r_bits_resp(slave_1_r_resp_delay),
    .io_slave_1_r_bits_id(slave_1_r_id_delay),
    .io_slave_1_r_bits_data(slave_1_r_data_delay),
    .io_slave_1_r_bits_last(slave_1_r_last_delay),

    .io_slave_1_b_valid(slave_1_b_valid_delay),
    .io_slave_1_b_ready(slave_1_b_ready_delay),
    .io_slave_1_b_bits_resp(slave_1_b_resp_delay),
    .io_slave_1_b_bits_id(slave_1_b_id_delay),

    .io_slave_2_ar_valid(slave_2_ar_valid_delay),
    .io_slave_2_ar_ready(slave_2_ar_ready_delay),
    .io_slave_2_ar_bits_addr(slave_2_ar_addr_delay),
    .io_slave_2_ar_bits_id(slave_2_ar_id_delay),
    .io_slave_2_ar_bits_size(slave_2_ar_size_delay),
    .io_slave_2_ar_bits_len(slave_2_ar_len_delay),

    .io_slave_2_aw_valid(slave_2_aw_valid_delay),
    .io_slave_2_aw_ready(slave_2_aw_ready_delay),
    .io_slave_2_aw_bits_addr(slave_2_aw_addr_delay),
    .io_slave_2_aw_bits_id(slave_2_aw_id_delay),
    .io_slave_2_aw_bits_size(slave_2_aw_size_delay),
    .io_slave_2_aw_bits_len(slave_2_aw_len_delay),

    .io_slave_2_w_valid(slave_2_w_valid_delay),
    .io_slave_2_w_ready(slave_2_w_ready_delay),
    .io_slave_2_w_bits_strb(slave_2_w_strb_delay),
    .io_slave_2_w_bits_data(slave_2_w_data_delay),
    .io_slave_2_w_bits_last(slave_2_w_last_delay),

    .io_slave_2_r_valid(slave_2_r_valid_delay),
    .io_slave_2_r_ready(slave_2_r_ready_delay),
    .io_slave_2_r_bits_resp(slave_2_r_resp_delay),
    .io_slave_2_r_bits_id(slave_2_r_id_delay),
    .io_slave_2_r_bits_data(slave_2_r_data_delay),
    .io_slave_2_r_bits_last(slave_2_r_last_delay),

    .io_slave_2_b_valid(slave_2_b_valid_delay),
    .io_slave_2_b_ready(slave_2_b_ready_delay),
    .io_slave_2_b_bits_resp(slave_2_b_resp_delay),
    .io_slave_2_b_bits_id(slave_2_b_id_delay),

    .io_slave_3_ar_valid(slave_3_ar_valid_delay),
    .io_slave_3_ar_ready(slave_3_ar_ready_delay),
    .io_slave_3_ar_bits_addr(slave_3_ar_addr_delay),
    .io_slave_3_ar_bits_id(slave_3_ar_id_delay),
    .io_slave_3_ar_bits_size(slave_3_ar_size_delay),
    .io_slave_3_ar_bits_len(slave_3_ar_len_delay),

    .io_slave_3_aw_valid(slave_3_aw_valid_delay),
    .io_slave_3_aw_ready(slave_3_aw_ready_delay),
    .io_slave_3_aw_bits_addr(slave_3_aw_addr_delay),
    .io_slave_3_aw_bits_id(slave_3_aw_id_delay),
    .io_slave_3_aw_bits_size(slave_3_aw_size_delay),
    .io_slave_3_aw_bits_len(slave_3_aw_len_delay),

    .io_slave_3_w_valid(slave_3_w_valid_delay),
    .io_slave_3_w_ready(slave_3_w_ready_delay),
    .io_slave_3_w_bits_strb(slave_3_w_strb_delay),
    .io_slave_3_w_bits_data(slave_3_w_data_delay),
    .io_slave_3_w_bits_last(slave_3_w_last_delay),

    .io_slave_3_r_valid(slave_3_r_valid_delay),
    .io_slave_3_r_ready(slave_3_r_ready_delay),
    .io_slave_3_r_bits_resp(slave_3_r_resp_delay),
    .io_slave_3_r_bits_id(slave_3_r_id_delay),
    .io_slave_3_r_bits_data(slave_3_r_data_delay),
    .io_slave_3_r_bits_last(slave_3_r_last_delay),

    .io_slave_3_b_valid(slave_3_b_valid_delay),
    .io_slave_3_b_ready(slave_3_b_ready_delay),
    .io_slave_3_b_bits_resp(slave_3_b_resp_delay),
    .io_slave_3_b_bits_id(slave_3_b_id_delay)
  );

  always @(posedge clock) begin
    trace_count = trace_count + 1;
    tick(
      reset,
      fin,

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

      dma_ar_valid,
      dma_ar_ready,
      dma_ar_addr,
      dma_ar_id,
      dma_ar_size,
      dma_ar_len,

      dma_aw_valid,
      dma_aw_ready,
      dma_aw_addr,
      dma_aw_id,
      dma_aw_size,
      dma_aw_len,

      dma_w_valid,
      dma_w_ready,
      dma_w_strb,
      dma_w_data,
      dma_w_last,

      dma_r_valid,
      dma_r_ready,
      dma_r_resp,
      dma_r_id,
      dma_r_data,
      dma_r_last,

      dma_b_valid,
      dma_b_ready,
      dma_b_resp,
      dma_b_id,

      slave_0_ar_valid,
      slave_0_ar_ready,
      slave_0_ar_addr,
      slave_0_ar_id,
      slave_0_ar_size,
      slave_0_ar_len,

      slave_0_aw_valid,
      slave_0_aw_ready,
      slave_0_aw_addr,
      slave_0_aw_id,
      slave_0_aw_size,
      slave_0_aw_len,

      slave_0_w_valid,
      slave_0_w_ready,
      slave_0_w_strb,
      slave_0_w_data,
      slave_0_w_last,

      slave_0_r_valid,
      slave_0_r_ready,
      slave_0_r_resp,
      slave_0_r_id,
      slave_0_r_data,
      slave_0_r_last,

      slave_0_b_valid,
      slave_0_b_ready,
      slave_0_b_resp,
      slave_0_b_id,

      slave_1_ar_valid,
      slave_1_ar_ready,
      slave_1_ar_addr,
      slave_1_ar_id,
      slave_1_ar_size,
      slave_1_ar_len,

      slave_1_aw_valid,
      slave_1_aw_ready,
      slave_1_aw_addr,
      slave_1_aw_id,
      slave_1_aw_size,
      slave_1_aw_len,

      slave_1_w_valid,
      slave_1_w_ready,
      slave_1_w_strb,
      slave_1_w_data,
      slave_1_w_last,

      slave_1_r_valid,
      slave_1_r_ready,
      slave_1_r_resp,
      slave_1_r_id,
      slave_1_r_data,
      slave_1_r_last,

      slave_1_b_valid,
      slave_1_b_ready,
      slave_1_b_resp,
      slave_1_b_id,

      slave_2_ar_valid,
      slave_2_ar_ready,
      slave_2_ar_addr,
      slave_2_ar_id,
      slave_2_ar_size,
      slave_2_ar_len,

      slave_2_aw_valid,
      slave_2_aw_ready,
      slave_2_aw_addr,
      slave_2_aw_id,
      slave_2_aw_size,
      slave_2_aw_len,

      slave_2_w_valid,
      slave_2_w_ready,
      slave_2_w_strb,
      slave_2_w_data,
      slave_2_w_last,

      slave_2_r_valid,
      slave_2_r_ready,
      slave_2_r_resp,
      slave_2_r_id,
      slave_2_r_data,
      slave_2_r_last,

      slave_2_b_valid,
      slave_2_b_ready,
      slave_2_b_resp,
      slave_2_b_id,

      slave_3_ar_valid,
      slave_3_ar_ready,
      slave_3_ar_addr,
      slave_3_ar_id,
      slave_3_ar_size,
      slave_3_ar_len,

      slave_3_aw_valid,
      slave_3_aw_ready,
      slave_3_aw_addr,
      slave_3_aw_id,
      slave_3_aw_size,
      slave_3_aw_len,

      slave_3_w_valid,
      slave_3_w_ready,
      slave_3_w_strb,
      slave_3_w_data,
      slave_3_w_last,

      slave_3_r_valid,
      slave_3_r_ready,
      slave_3_r_resp,
      slave_3_r_id,
      slave_3_r_data,
      slave_3_r_last,

      slave_3_b_valid,
      slave_3_b_ready,
      slave_3_b_resp,
      slave_3_b_id
    );
  end
endmodule;
