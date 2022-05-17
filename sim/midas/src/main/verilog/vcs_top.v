extern "A" void tick
(
  output reg                       reset,
  output reg                       fin,

  output reg                       ctrl_ar_valid,
  input  reg                       ctrl_ar_ready,
  output reg [`CTRL_ADDR_BITS-1:0] ctrl_ar_addr,
  output reg [`CTRL_ID_BITS-1:0]   ctrl_ar_id,
  output reg [2:0]                 ctrl_ar_size,
  output reg [7:0]                 ctrl_ar_len,

  output reg                       ctrl_aw_valid,
  input  reg                       ctrl_aw_ready,
  output reg [`CTRL_ADDR_BITS-1:0] ctrl_aw_addr,
  output reg [`CTRL_ID_BITS-1:0]   ctrl_aw_id,
  output reg [2:0]                 ctrl_aw_size,
  output reg [7:0]                 ctrl_aw_len,

  output reg                       ctrl_w_valid,
  input  reg                       ctrl_w_ready,
  output reg [`CTRL_STRB_BITS-1:0] ctrl_w_strb,
  output reg [`CTRL_DATA_BITS-1:0] ctrl_w_data,
  output reg                       ctrl_w_last,

  input  reg                       ctrl_r_valid,
  output reg                       ctrl_r_ready,
  input  reg [1:0]                 ctrl_r_resp,
  input  reg [`CTRL_ID_BITS-1:0]   ctrl_r_id,
  input  reg [`CTRL_DATA_BITS-1:0] ctrl_r_data,
  input  reg                       ctrl_r_last,

  input  reg                       ctrl_b_valid,
  output reg                       ctrl_b_ready,
  input  reg [1:0]                 ctrl_b_resp,
  input  reg [`CTRL_ID_BITS-1:0]   ctrl_b_id,

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

  input  reg                       mem_0_ar_valid,
  output reg                       mem_0_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  mem_0_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]    mem_0_ar_id,
  input  reg [2:0]                 mem_0_ar_size,
  input  reg [7:0]                 mem_0_ar_len,

  input  reg                       mem_0_aw_valid,
  output reg                       mem_0_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  mem_0_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]    mem_0_aw_id,
  input  reg [2:0]                 mem_0_aw_size,
  input  reg [7:0]                 mem_0_aw_len,

  input  reg                       mem_0_w_valid,
  output reg                       mem_0_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]  mem_0_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]  mem_0_w_data,
  input  reg                       mem_0_w_last,

  output reg                       mem_0_r_valid,
  input  reg                       mem_0_r_ready,
  output reg [1:0]                 mem_0_r_resp,
  output reg [`MEM_ID_BITS-1:0]    mem_0_r_id,
  output reg [`MEM_DATA_BITS-1:0]  mem_0_r_data,
  output reg                       mem_0_r_last,

  output reg                       mem_0_b_valid,
  input  reg                       mem_0_b_ready,
  output reg [1:0]                 mem_0_b_resp,
  output reg [`MEM_ID_BITS-1:0]    mem_0_b_id,

  input  reg                       mem_1_ar_valid,
  output reg                       mem_1_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  mem_1_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]    mem_1_ar_id,
  input  reg [2:0]                 mem_1_ar_size,
  input  reg [7:0]                 mem_1_ar_len,

  input  reg                       mem_1_aw_valid,
  output reg                       mem_1_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  mem_1_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]    mem_1_aw_id,
  input  reg [2:0]                 mem_1_aw_size,
  input  reg [7:0]                 mem_1_aw_len,

  input  reg                       mem_1_w_valid,
  output reg                       mem_1_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]  mem_1_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]  mem_1_w_data,
  input  reg                       mem_1_w_last,

  output reg                       mem_1_r_valid,
  input  reg                       mem_1_r_ready,
  output reg [1:0]                 mem_1_r_resp,
  output reg [`MEM_ID_BITS-1:0]    mem_1_r_id,
  output reg [`MEM_DATA_BITS-1:0]  mem_1_r_data,
  output reg                       mem_1_r_last,

  output reg                       mem_1_b_valid,
  input  reg                       mem_1_b_ready,
  output reg [1:0]                 mem_1_b_resp,
  output reg [`MEM_ID_BITS-1:0]    mem_1_b_id,

  input  reg                       mem_2_ar_valid,
  output reg                       mem_2_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  mem_2_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]    mem_2_ar_id,
  input  reg [2:0]                 mem_2_ar_size,
  input  reg [7:0]                 mem_2_ar_len,

  input  reg                       mem_2_aw_valid,
  output reg                       mem_2_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  mem_2_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]    mem_2_aw_id,
  input  reg [2:0]                 mem_2_aw_size,
  input  reg [7:0]                 mem_2_aw_len,

  input  reg                       mem_2_w_valid,
  output reg                       mem_2_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]  mem_2_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]  mem_2_w_data,
  input  reg                       mem_2_w_last,

  output reg                       mem_2_r_valid,
  input  reg                       mem_2_r_ready,
  output reg [1:0]                 mem_2_r_resp,
  output reg [`MEM_ID_BITS-1:0]    mem_2_r_id,
  output reg [`MEM_DATA_BITS-1:0]  mem_2_r_data,
  output reg                       mem_2_r_last,

  output reg                       mem_2_b_valid,
  input  reg                       mem_2_b_ready,
  output reg [1:0]                 mem_2_b_resp,
  output reg [`MEM_ID_BITS-1:0]    mem_2_b_id,

  input  reg                       mem_3_ar_valid,
  output reg                       mem_3_ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  mem_3_ar_addr,
  input  reg [`MEM_ID_BITS-1:0]    mem_3_ar_id,
  input  reg [2:0]                 mem_3_ar_size,
  input  reg [7:0]                 mem_3_ar_len,

  input  reg                       mem_3_aw_valid,
  output reg                       mem_3_aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0]  mem_3_aw_addr,
  input  reg [`MEM_ID_BITS-1:0]    mem_3_aw_id,
  input  reg [2:0]                 mem_3_aw_size,
  input  reg [7:0]                 mem_3_aw_len,

  input  reg                       mem_3_w_valid,
  output reg                       mem_3_w_ready,
  input  reg [`MEM_STRB_BITS-1:0]  mem_3_w_strb,
  input  reg [`MEM_DATA_BITS-1:0]  mem_3_w_data,
  input  reg                       mem_3_w_last,

  output reg                       mem_3_r_valid,
  input  reg                       mem_3_r_ready,
  output reg [1:0]                 mem_3_r_resp,
  output reg [`MEM_ID_BITS-1:0]    mem_3_r_id,
  output reg [`MEM_DATA_BITS-1:0]  mem_3_r_data,
  output reg                       mem_3_r_last,

  output reg                       mem_3_b_valid,
  input  reg                       mem_3_b_ready,
  output reg [1:0]                 mem_3_b_resp,
  output reg [`MEM_ID_BITS-1:0]    mem_3_b_id
);

module emul;
  reg clock = 1'b0;
  reg reset = 1'b1;
  reg fin = 1'b0;

  always #(`CLOCK_PERIOD / 2.0) clock = ~clock;

  reg [2047:0] vcdplusfile = 2048'h0;
  reg [63:0] dump_start = 64'h0;
  reg [63:0] dump_end = {64{1'b1}};
  reg [63:0] dump_cycles = 64'h0;
  reg [63:0] trace_count = 64'h0;

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

  reg                        ctrl_ar_valid;
  wire                       ctrl_ar_ready;
  reg  [`CTRL_ADDR_BITS-1:0] ctrl_ar_addr;
  reg  [`CTRL_ID_BITS-1:0]   ctrl_ar_id;
  reg  [2:0]                 ctrl_ar_size;
  reg  [7:0]                 ctrl_ar_len;

  reg                        ctrl_aw_valid;
  wire                       ctrl_aw_ready;
  reg  [`CTRL_ADDR_BITS-1:0] ctrl_aw_addr;
  reg  [`CTRL_ID_BITS-1:0]   ctrl_aw_id;
  reg  [2:0]                 ctrl_aw_size;
  reg  [7:0]                 ctrl_aw_len;

  reg                        ctrl_w_valid;
  wire                       ctrl_w_ready;
  reg  [`CTRL_STRB_BITS-1:0] ctrl_w_strb;
  reg  [`CTRL_DATA_BITS-1:0] ctrl_w_data;
  reg                        ctrl_w_last;

  wire                       ctrl_r_valid;
  reg                        ctrl_r_ready;
  wire [1:0]                 ctrl_r_resp;
  wire [`CTRL_ID_BITS-1:0]   ctrl_r_id;
  wire [`CTRL_DATA_BITS-1:0] ctrl_r_data;
  wire                       ctrl_r_last;

  wire                       ctrl_b_valid;
  reg                        ctrl_b_ready;
  wire [1:0]                 ctrl_b_resp;
  wire [`CTRL_ID_BITS-1:0]   ctrl_b_id;

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

  wire                       mem_0_ar_valid;
  reg                        mem_0_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]  mem_0_ar_addr;
  wire [`MEM_ID_BITS-1:0]    mem_0_ar_id;
  wire [2:0]                 mem_0_ar_size;
  wire [7:0]                 mem_0_ar_len;

  wire                       mem_0_aw_valid;
  reg                        mem_0_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]  mem_0_aw_addr;
  wire [`MEM_ID_BITS-1:0]    mem_0_aw_id;
  wire [2:0]                 mem_0_aw_size;
  wire [7:0]                 mem_0_aw_len;

  wire                       mem_0_w_valid;
  reg                        mem_0_w_ready;
  wire [`MEM_STRB_BITS-1:0]  mem_0_w_strb;
  wire [`MEM_DATA_BITS-1:0]  mem_0_w_data;
  wire                       mem_0_w_last;

  reg                        mem_0_r_valid;
  wire                       mem_0_r_ready;
  reg  [1:0]                 mem_0_r_resp;
  reg  [`MEM_ID_BITS-1:0]    mem_0_r_id;
  reg  [`MEM_DATA_BITS-1:0]  mem_0_r_data;
  reg                        mem_0_r_last;

  reg                        mem_0_b_valid;
  wire                       mem_0_b_ready;
  reg  [1:0]                 mem_0_b_resp;
  reg  [`MEM_ID_BITS-1:0]    mem_0_b_id;

  wire                       mem_1_ar_valid;
  reg                        mem_1_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]  mem_1_ar_addr;
  wire [`MEM_ID_BITS-1:0]    mem_1_ar_id;
  wire [2:0]                 mem_1_ar_size;
  wire [7:0]                 mem_1_ar_len;

  wire                       mem_1_aw_valid;
  reg                        mem_1_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]  mem_1_aw_addr;
  wire [`MEM_ID_BITS-1:0]    mem_1_aw_id;
  wire [2:0]                 mem_1_aw_size;
  wire [7:0]                 mem_1_aw_len;

  wire                       mem_1_w_valid;
  reg                        mem_1_w_ready;
  wire [`MEM_STRB_BITS-1:0]  mem_1_w_strb;
  wire [`MEM_DATA_BITS-1:0]  mem_1_w_data;
  wire                       mem_1_w_last;

  reg                        mem_1_r_valid;
  wire                       mem_1_r_ready;
  reg  [1:0]                 mem_1_r_resp;
  reg  [`MEM_ID_BITS-1:0]    mem_1_r_id;
  reg  [`MEM_DATA_BITS-1:0]  mem_1_r_data;
  reg                        mem_1_r_last;

  reg                        mem_1_b_valid;
  wire                       mem_1_b_ready;
  reg  [1:0]                 mem_1_b_resp;
  reg  [`MEM_ID_BITS-1:0]    mem_1_b_id;

  wire                       mem_2_ar_valid;
  reg                        mem_2_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]  mem_2_ar_addr;
  wire [`MEM_ID_BITS-1:0]    mem_2_ar_id;
  wire [2:0]                 mem_2_ar_size;
  wire [7:0]                 mem_2_ar_len;

  wire                       mem_2_aw_valid;
  reg                        mem_2_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]  mem_2_aw_addr;
  wire [`MEM_ID_BITS-1:0]    mem_2_aw_id;
  wire [2:0]                 mem_2_aw_size;
  wire [7:0]                 mem_2_aw_len;

  wire                       mem_2_w_valid;
  reg                        mem_2_w_ready;
  wire [`MEM_STRB_BITS-1:0]  mem_2_w_strb;
  wire [`MEM_DATA_BITS-1:0]  mem_2_w_data;
  wire                       mem_2_w_last;

  reg                        mem_2_r_valid;
  wire                       mem_2_r_ready;
  reg  [1:0]                 mem_2_r_resp;
  reg  [`MEM_ID_BITS-1:0]    mem_2_r_id;
  reg  [`MEM_DATA_BITS-1:0]  mem_2_r_data;
  reg                        mem_2_r_last;

  reg                        mem_2_b_valid;
  wire                       mem_2_b_ready;
  reg  [1:0]                 mem_2_b_resp;
  reg  [`MEM_ID_BITS-1:0]    mem_2_b_id;

  wire                       mem_3_ar_valid;
  reg                        mem_3_ar_ready;
  wire [`MEM_ADDR_BITS-1:0]  mem_3_ar_addr;
  wire [`MEM_ID_BITS-1:0]    mem_3_ar_id;
  wire [2:0]                 mem_3_ar_size;
  wire [7:0]                 mem_3_ar_len;

  wire                       mem_3_aw_valid;
  reg                        mem_3_aw_ready;
  wire [`MEM_ADDR_BITS-1:0]  mem_3_aw_addr;
  wire [`MEM_ID_BITS-1:0]    mem_3_aw_id;
  wire [2:0]                 mem_3_aw_size;
  wire [7:0]                 mem_3_aw_len;

  wire                       mem_3_w_valid;
  reg                        mem_3_w_ready;
  wire [`MEM_STRB_BITS-1:0]  mem_3_w_strb;
  wire [`MEM_DATA_BITS-1:0]  mem_3_w_data;
  wire                       mem_3_w_last;

  reg                        mem_3_r_valid;
  wire                       mem_3_r_ready;
  reg  [1:0]                 mem_3_r_resp;
  reg  [`MEM_ID_BITS-1:0]    mem_3_r_id;
  reg  [`MEM_DATA_BITS-1:0]  mem_3_r_data;
  reg                        mem_3_r_last;

  reg                        mem_3_b_valid;
  wire                       mem_3_b_ready;
  reg  [1:0]                 mem_3_b_resp;
  reg  [`MEM_ID_BITS-1:0]    mem_3_b_id;

  wire                       reset_delay;

  wire                       ctrl_ar_valid_delay;
  wire                       ctrl_ar_ready_delay;
  wire [`CTRL_ADDR_BITS-1:0] ctrl_ar_addr_delay;
  wire [`CTRL_ID_BITS-1:0]   ctrl_ar_id_delay;
  wire [2:0]                 ctrl_ar_size_delay;
  wire [7:0]                 ctrl_ar_len_delay;

  wire                       ctrl_aw_valid_delay;
  wire                       ctrl_aw_ready_delay;
  wire [`CTRL_ADDR_BITS-1:0] ctrl_aw_addr_delay;
  wire [`CTRL_ID_BITS-1:0]   ctrl_aw_id_delay;
  wire [2:0]                 ctrl_aw_size_delay;
  wire [7:0]                 ctrl_aw_len_delay;

  wire                       ctrl_w_valid_delay;
  wire                       ctrl_w_ready_delay;
  wire [`CTRL_STRB_BITS-1:0] ctrl_w_strb_delay;
  wire [`CTRL_DATA_BITS-1:0] ctrl_w_data_delay;
  wire                       ctrl_w_last_delay;

  wire                       ctrl_r_valid_delay;
  wire                       ctrl_r_ready_delay;
  wire [1:0]                 ctrl_r_resp_delay;
  wire [`CTRL_ID_BITS-1:0]   ctrl_r_id_delay;
  wire [`CTRL_DATA_BITS-1:0] ctrl_r_data_delay;
  wire                       ctrl_r_last_delay;

  wire                       ctrl_b_valid_delay;
  wire                       ctrl_b_ready_delay;
  wire [1:0]                 ctrl_b_resp_delay;
  wire [`CTRL_ID_BITS-1:0]   ctrl_b_id_delay;

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

  wire                       mem_0_ar_valid_delay;
  wire                       mem_0_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  mem_0_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]    mem_0_ar_id_delay;
  wire [2:0]                 mem_0_ar_size_delay;
  wire [7:0]                 mem_0_ar_len_delay;

  wire                       mem_0_aw_valid_delay;
  wire                       mem_0_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  mem_0_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]    mem_0_aw_id_delay;
  wire [2:0]                 mem_0_aw_size_delay;
  wire [7:0]                 mem_0_aw_len_delay;

  wire                       mem_0_w_valid_delay;
  wire                       mem_0_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]  mem_0_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]  mem_0_w_data_delay;
  wire                       mem_0_w_last_delay;

  wire                       mem_0_r_valid_delay;
  wire                       mem_0_r_ready_delay;
  wire [1:0]                 mem_0_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]    mem_0_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]  mem_0_r_data_delay;
  wire                       mem_0_r_last_delay;

  wire                       mem_0_b_valid_delay;
  wire                       mem_0_b_ready_delay;
  wire [1:0]                 mem_0_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]    mem_0_b_id_delay;

  wire                       mem_1_ar_valid_delay;
  wire                       mem_1_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  mem_1_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]    mem_1_ar_id_delay;
  wire [2:0]                 mem_1_ar_size_delay;
  wire [7:0]                 mem_1_ar_len_delay;

  wire                       mem_1_aw_valid_delay;
  wire                       mem_1_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  mem_1_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]    mem_1_aw_id_delay;
  wire [2:0]                 mem_1_aw_size_delay;
  wire [7:0]                 mem_1_aw_len_delay;

  wire                       mem_1_w_valid_delay;
  wire                       mem_1_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]  mem_1_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]  mem_1_w_data_delay;
  wire                       mem_1_w_last_delay;

  wire                       mem_1_r_valid_delay;
  wire                       mem_1_r_ready_delay;
  wire [1:0]                 mem_1_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]    mem_1_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]  mem_1_r_data_delay;
  wire                       mem_1_r_last_delay;

  wire                       mem_1_b_valid_delay;
  wire                       mem_1_b_ready_delay;
  wire [1:0]                 mem_1_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]    mem_1_b_id_delay;

  wire                       mem_2_ar_valid_delay;
  wire                       mem_2_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  mem_2_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]    mem_2_ar_id_delay;
  wire [2:0]                 mem_2_ar_size_delay;
  wire [7:0]                 mem_2_ar_len_delay;

  wire                       mem_2_aw_valid_delay;
  wire                       mem_2_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  mem_2_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]    mem_2_aw_id_delay;
  wire [2:0]                 mem_2_aw_size_delay;
  wire [7:0]                 mem_2_aw_len_delay;

  wire                       mem_2_w_valid_delay;
  wire                       mem_2_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]  mem_2_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]  mem_2_w_data_delay;
  wire                       mem_2_w_last_delay;

  wire                       mem_2_r_valid_delay;
  wire                       mem_2_r_ready_delay;
  wire [1:0]                 mem_2_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]    mem_2_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]  mem_2_r_data_delay;
  wire                       mem_2_r_last_delay;

  wire                       mem_2_b_valid_delay;
  wire                       mem_2_b_ready_delay;
  wire [1:0]                 mem_2_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]    mem_2_b_id_delay;

  wire                       mem_3_ar_valid_delay;
  wire                       mem_3_ar_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  mem_3_ar_addr_delay;
  wire [`MEM_ID_BITS-1:0]    mem_3_ar_id_delay;
  wire [2:0]                 mem_3_ar_size_delay;
  wire [7:0]                 mem_3_ar_len_delay;

  wire                       mem_3_aw_valid_delay;
  wire                       mem_3_aw_ready_delay;
  wire [`MEM_ADDR_BITS-1:0]  mem_3_aw_addr_delay;
  wire [`MEM_ID_BITS-1:0]    mem_3_aw_id_delay;
  wire [2:0]                 mem_3_aw_size_delay;
  wire [7:0]                 mem_3_aw_len_delay;

  wire                       mem_3_w_valid_delay;
  wire                       mem_3_w_ready_delay;
  wire [`MEM_STRB_BITS-1:0]  mem_3_w_strb_delay;
  wire [`MEM_DATA_BITS-1:0]  mem_3_w_data_delay;
  wire                       mem_3_w_last_delay;

  wire                       mem_3_r_valid_delay;
  wire                       mem_3_r_ready_delay;
  wire [1:0]                 mem_3_r_resp_delay;
  wire [`MEM_ID_BITS-1:0]    mem_3_r_id_delay;
  wire [`MEM_DATA_BITS-1:0]  mem_3_r_data_delay;
  wire                       mem_3_r_last_delay;

  wire                       mem_3_b_valid_delay;
  wire                       mem_3_b_ready_delay;
  wire [1:0]                 mem_3_b_resp_delay;
  wire [`MEM_ID_BITS-1:0]    mem_3_b_id_delay;

  assign #0.1 ctrl_ar_valid_delay = ctrl_ar_valid;
  assign #0.1 ctrl_ar_ready = ctrl_ar_ready_delay;
  assign #0.1 ctrl_ar_addr_delay = ctrl_ar_addr;
  assign #0.1 ctrl_ar_id_delay = ctrl_ar_id;
  assign #0.1 ctrl_ar_size_delay = ctrl_ar_size;
  assign #0.1 ctrl_ar_len_delay = ctrl_ar_len;

  assign #0.1 ctrl_aw_valid_delay = ctrl_aw_valid;
  assign #0.1 ctrl_aw_ready = ctrl_aw_ready_delay;
  assign #0.1 ctrl_aw_addr_delay = ctrl_aw_addr;
  assign #0.1 ctrl_aw_id_delay = ctrl_aw_id;
  assign #0.1 ctrl_aw_size_delay = ctrl_aw_size;
  assign #0.1 ctrl_aw_len_delay = ctrl_aw_len;

  assign #0.1 ctrl_w_valid_delay = ctrl_w_valid;
  assign #0.1 ctrl_w_ready = ctrl_w_ready_delay;
  assign #0.1 ctrl_w_strb_delay = ctrl_w_strb;
  assign #0.1 ctrl_w_data_delay = ctrl_w_data;
  assign #0.1 ctrl_w_last_delay = ctrl_w_last;

  assign #0.1 ctrl_r_valid = ctrl_r_valid_delay;
  assign #0.1 ctrl_r_ready_delay = ctrl_r_ready;
  assign #0.1 ctrl_r_resp = ctrl_r_resp_delay;
  assign #0.1 ctrl_r_id = ctrl_r_id_delay;
  assign #0.1 ctrl_r_data = ctrl_r_data_delay;
  assign #0.1 ctrl_r_last = ctrl_r_last_delay;

  assign #0.1 ctrl_b_valid = ctrl_b_valid_delay;
  assign #0.1 ctrl_b_ready_delay = ctrl_b_ready;
  assign #0.1 ctrl_b_resp = ctrl_b_resp_delay;
  assign #0.1 ctrl_b_id = ctrl_b_id_delay;

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

  assign #0.1 mem_0_ar_valid = mem_0_ar_valid_delay;
  assign #0.1 mem_0_ar_ready_delay = mem_0_ar_ready;
  assign #0.1 mem_0_ar_addr = mem_0_ar_addr_delay;
  assign #0.1 mem_0_ar_id = mem_0_ar_id_delay;
  assign #0.1 mem_0_ar_size = mem_0_ar_size_delay;
  assign #0.1 mem_0_ar_len = mem_0_ar_len_delay;

  assign #0.1 mem_0_aw_valid = mem_0_aw_valid_delay;
  assign #0.1 mem_0_aw_ready_delay = mem_0_aw_ready;
  assign #0.1 mem_0_aw_addr = mem_0_aw_addr_delay;
  assign #0.1 mem_0_aw_id = mem_0_aw_id_delay;
  assign #0.1 mem_0_aw_size = mem_0_aw_size_delay;
  assign #0.1 mem_0_aw_len = mem_0_aw_len_delay;

  assign #0.1 mem_0_w_valid = mem_0_w_valid_delay;
  assign #0.1 mem_0_w_ready_delay = mem_0_w_ready;
  assign #0.1 mem_0_w_strb = mem_0_w_strb_delay;
  assign #0.1 mem_0_w_data = mem_0_w_data_delay;
  assign #0.1 mem_0_w_last = mem_0_w_last_delay;

  assign #0.1 mem_0_r_valid_delay = mem_0_r_valid;
  assign #0.1 mem_0_r_ready = mem_0_r_ready_delay;
  assign #0.1 mem_0_r_resp_delay = mem_0_r_resp;
  assign #0.1 mem_0_r_id_delay = mem_0_r_id;
  assign #0.1 mem_0_r_data_delay = mem_0_r_data;
  assign #0.1 mem_0_r_last_delay = mem_0_r_last;

  assign #0.1 mem_0_b_valid_delay = mem_0_b_valid;
  assign #0.1 mem_0_b_ready = mem_0_b_ready_delay;
  assign #0.1 mem_0_b_resp_delay = mem_0_b_resp;
  assign #0.1 mem_0_b_id_delay = mem_0_b_id;

  assign #0.1 mem_1_ar_valid = mem_1_ar_valid_delay;
  assign #0.1 mem_1_ar_ready_delay = mem_1_ar_ready;
  assign #0.1 mem_1_ar_addr = mem_1_ar_addr_delay;
  assign #0.1 mem_1_ar_id = mem_1_ar_id_delay;
  assign #0.1 mem_1_ar_size = mem_1_ar_size_delay;
  assign #0.1 mem_1_ar_len = mem_1_ar_len_delay;

  assign #0.1 mem_1_aw_valid = mem_1_aw_valid_delay;
  assign #0.1 mem_1_aw_ready_delay = mem_1_aw_ready;
  assign #0.1 mem_1_aw_addr = mem_1_aw_addr_delay;
  assign #0.1 mem_1_aw_id = mem_1_aw_id_delay;
  assign #0.1 mem_1_aw_size = mem_1_aw_size_delay;
  assign #0.1 mem_1_aw_len = mem_1_aw_len_delay;

  assign #0.1 mem_1_w_valid = mem_1_w_valid_delay;
  assign #0.1 mem_1_w_ready_delay = mem_1_w_ready;
  assign #0.1 mem_1_w_strb = mem_1_w_strb_delay;
  assign #0.1 mem_1_w_data = mem_1_w_data_delay;
  assign #0.1 mem_1_w_last = mem_1_w_last_delay;

  assign #0.1 mem_1_r_valid_delay = mem_1_r_valid;
  assign #0.1 mem_1_r_ready = mem_1_r_ready_delay;
  assign #0.1 mem_1_r_resp_delay = mem_1_r_resp;
  assign #0.1 mem_1_r_id_delay = mem_1_r_id;
  assign #0.1 mem_1_r_data_delay = mem_1_r_data;
  assign #0.1 mem_1_r_last_delay = mem_1_r_last;

  assign #0.1 mem_1_b_valid_delay = mem_1_b_valid;
  assign #0.1 mem_1_b_ready = mem_1_b_ready_delay;
  assign #0.1 mem_1_b_resp_delay = mem_1_b_resp;
  assign #0.1 mem_1_b_id_delay = mem_1_b_id;

  assign #0.1 mem_2_ar_valid = mem_2_ar_valid_delay;
  assign #0.1 mem_2_ar_ready_delay = mem_2_ar_ready;
  assign #0.1 mem_2_ar_addr = mem_2_ar_addr_delay;
  assign #0.1 mem_2_ar_id = mem_2_ar_id_delay;
  assign #0.1 mem_2_ar_size = mem_2_ar_size_delay;
  assign #0.1 mem_2_ar_len = mem_2_ar_len_delay;

  assign #0.1 mem_2_aw_valid = mem_2_aw_valid_delay;
  assign #0.1 mem_2_aw_ready_delay = mem_2_aw_ready;
  assign #0.1 mem_2_aw_addr = mem_2_aw_addr_delay;
  assign #0.1 mem_2_aw_id = mem_2_aw_id_delay;
  assign #0.1 mem_2_aw_size = mem_2_aw_size_delay;
  assign #0.1 mem_2_aw_len = mem_2_aw_len_delay;

  assign #0.1 mem_2_w_valid = mem_2_w_valid_delay;
  assign #0.1 mem_2_w_ready_delay = mem_2_w_ready;
  assign #0.1 mem_2_w_strb = mem_2_w_strb_delay;
  assign #0.1 mem_2_w_data = mem_2_w_data_delay;
  assign #0.1 mem_2_w_last = mem_2_w_last_delay;

  assign #0.1 mem_2_r_valid_delay = mem_2_r_valid;
  assign #0.1 mem_2_r_ready = mem_2_r_ready_delay;
  assign #0.1 mem_2_r_resp_delay = mem_2_r_resp;
  assign #0.1 mem_2_r_id_delay = mem_2_r_id;
  assign #0.1 mem_2_r_data_delay = mem_2_r_data;
  assign #0.1 mem_2_r_last_delay = mem_2_r_last;

  assign #0.1 mem_2_b_valid_delay = mem_2_b_valid;
  assign #0.1 mem_2_b_ready = mem_2_b_ready_delay;
  assign #0.1 mem_2_b_resp_delay = mem_2_b_resp;
  assign #0.1 mem_2_b_id_delay = mem_2_b_id;

  assign #0.1 mem_3_ar_valid = mem_3_ar_valid_delay;
  assign #0.1 mem_3_ar_ready_delay = mem_3_ar_ready;
  assign #0.1 mem_3_ar_addr = mem_3_ar_addr_delay;
  assign #0.1 mem_3_ar_id = mem_3_ar_id_delay;
  assign #0.1 mem_3_ar_size = mem_3_ar_size_delay;
  assign #0.1 mem_3_ar_len = mem_3_ar_len_delay;

  assign #0.1 mem_3_aw_valid = mem_3_aw_valid_delay;
  assign #0.1 mem_3_aw_ready_delay = mem_3_aw_ready;
  assign #0.1 mem_3_aw_addr = mem_3_aw_addr_delay;
  assign #0.1 mem_3_aw_id = mem_3_aw_id_delay;
  assign #0.1 mem_3_aw_size = mem_3_aw_size_delay;
  assign #0.1 mem_3_aw_len = mem_3_aw_len_delay;

  assign #0.1 mem_3_w_valid = mem_3_w_valid_delay;
  assign #0.1 mem_3_w_ready_delay = mem_3_w_ready;
  assign #0.1 mem_3_w_strb = mem_3_w_strb_delay;
  assign #0.1 mem_3_w_data = mem_3_w_data_delay;
  assign #0.1 mem_3_w_last = mem_3_w_last_delay;

  assign #0.1 mem_3_r_valid_delay = mem_3_r_valid;
  assign #0.1 mem_3_r_ready = mem_3_r_ready_delay;
  assign #0.1 mem_3_r_resp_delay = mem_3_r_resp;
  assign #0.1 mem_3_r_id_delay = mem_3_r_id;
  assign #0.1 mem_3_r_data_delay = mem_3_r_data;
  assign #0.1 mem_3_r_last_delay = mem_3_r_last;

  assign #0.1 mem_3_b_valid_delay = mem_3_b_valid;
  assign #0.1 mem_3_b_ready = mem_3_b_ready_delay;
  assign #0.1 mem_3_b_resp_delay = mem_3_b_resp;
  assign #0.1 mem_3_b_id_delay = mem_3_b_id;

  assign #0.1 reset_delay = reset;

  FPGATop FPGATop(

    .ctrl_ar_valid(ctrl_ar_valid_delay),
    .ctrl_ar_ready(ctrl_ar_ready_delay),
    .ctrl_ar_bits_addr(ctrl_ar_addr_delay),
    .ctrl_ar_bits_id(ctrl_ar_id_delay),
    .ctrl_ar_bits_size(ctrl_ar_size_delay),
    .ctrl_ar_bits_len(ctrl_ar_len_delay),

    .ctrl_aw_valid(ctrl_aw_valid_delay),
    .ctrl_aw_ready(ctrl_aw_ready_delay),
    .ctrl_aw_bits_addr(ctrl_aw_addr_delay),
    .ctrl_aw_bits_id(ctrl_aw_id_delay),
    .ctrl_aw_bits_size(ctrl_aw_size_delay),
    .ctrl_aw_bits_len(ctrl_aw_len_delay),

    .ctrl_w_valid(ctrl_w_valid_delay),
    .ctrl_w_ready(ctrl_w_ready_delay),
    .ctrl_w_bits_strb(ctrl_w_strb_delay),
    .ctrl_w_bits_data(ctrl_w_data_delay),
    .ctrl_w_bits_last(ctrl_w_last_delay),

    .ctrl_r_valid(ctrl_r_valid_delay),
    .ctrl_r_ready(ctrl_r_ready_delay),
    .ctrl_r_bits_resp(ctrl_r_resp_delay),
    .ctrl_r_bits_id(ctrl_r_id_delay),
    .ctrl_r_bits_data(ctrl_r_data_delay),
    .ctrl_r_bits_last(ctrl_r_last_delay),

    .ctrl_b_valid(ctrl_b_valid_delay),
    .ctrl_b_ready(ctrl_b_ready_delay),
    .ctrl_b_bits_resp(ctrl_b_resp_delay),
    .ctrl_b_bits_id(ctrl_b_id_delay),

    .dma_ar_valid(dma_ar_valid_delay),
    .dma_ar_ready(dma_ar_ready_delay),
    .dma_ar_bits_addr(dma_ar_addr_delay),
    .dma_ar_bits_id(dma_ar_id_delay),
    .dma_ar_bits_size(dma_ar_size_delay),
    .dma_ar_bits_len(dma_ar_len_delay),

    .dma_aw_valid(dma_aw_valid_delay),
    .dma_aw_ready(dma_aw_ready_delay),
    .dma_aw_bits_addr(dma_aw_addr_delay),
    .dma_aw_bits_id(dma_aw_id_delay),
    .dma_aw_bits_size(dma_aw_size_delay),
    .dma_aw_bits_len(dma_aw_len_delay),

    .dma_w_valid(dma_w_valid_delay),
    .dma_w_ready(dma_w_ready_delay),
    .dma_w_bits_strb(dma_w_strb_delay),
    .dma_w_bits_data(dma_w_data_delay),
    .dma_w_bits_last(dma_w_last_delay),

    .dma_r_valid(dma_r_valid_delay),
    .dma_r_ready(dma_r_ready_delay),
    .dma_r_bits_resp(dma_r_resp_delay),
    .dma_r_bits_id(dma_r_id_delay),
    .dma_r_bits_data(dma_r_data_delay),
    .dma_r_bits_last(dma_r_last_delay),

    .dma_b_valid(dma_b_valid_delay),
    .dma_b_ready(dma_b_ready_delay),
    .dma_b_bits_resp(dma_b_resp_delay),
    .dma_b_bits_id(dma_b_id_delay),

    .mem_0_ar_valid(mem_0_ar_valid_delay),
    .mem_0_ar_ready(mem_0_ar_ready_delay),
    .mem_0_ar_bits_addr(mem_0_ar_addr_delay),
    .mem_0_ar_bits_id(mem_0_ar_id_delay),
    .mem_0_ar_bits_size(mem_0_ar_size_delay),
    .mem_0_ar_bits_len(mem_0_ar_len_delay),

    .mem_0_aw_valid(mem_0_aw_valid_delay),
    .mem_0_aw_ready(mem_0_aw_ready_delay),
    .mem_0_aw_bits_addr(mem_0_aw_addr_delay),
    .mem_0_aw_bits_id(mem_0_aw_id_delay),
    .mem_0_aw_bits_size(mem_0_aw_size_delay),
    .mem_0_aw_bits_len(mem_0_aw_len_delay),

    .mem_0_w_valid(mem_0_w_valid_delay),
    .mem_0_w_ready(mem_0_w_ready_delay),
    .mem_0_w_bits_strb(mem_0_w_strb_delay),
    .mem_0_w_bits_data(mem_0_w_data_delay),
    .mem_0_w_bits_last(mem_0_w_last_delay),

    .mem_0_r_valid(mem_0_r_valid_delay),
    .mem_0_r_ready(mem_0_r_ready_delay),
    .mem_0_r_bits_resp(mem_0_r_resp_delay),
    .mem_0_r_bits_id(mem_0_r_id_delay),
    .mem_0_r_bits_data(mem_0_r_data_delay),
    .mem_0_r_bits_last(mem_0_r_last_delay),

    .mem_0_b_valid(mem_0_b_valid_delay),
    .mem_0_b_ready(mem_0_b_ready_delay),
    .mem_0_b_bits_resp(mem_0_b_resp_delay),
    .mem_0_b_bits_id(mem_0_b_id_delay),

`ifdef MEM_HAS_CHANNEL1
    .mem_1_ar_valid(mem_1_ar_valid_delay),
    .mem_1_ar_ready(mem_1_ar_ready_delay),
    .mem_1_ar_bits_addr(mem_1_ar_addr_delay),
    .mem_1_ar_bits_id(mem_1_ar_id_delay),
    .mem_1_ar_bits_size(mem_1_ar_size_delay),
    .mem_1_ar_bits_len(mem_1_ar_len_delay),

    .mem_1_aw_valid(mem_1_aw_valid_delay),
    .mem_1_aw_ready(mem_1_aw_ready_delay),
    .mem_1_aw_bits_addr(mem_1_aw_addr_delay),
    .mem_1_aw_bits_id(mem_1_aw_id_delay),
    .mem_1_aw_bits_size(mem_1_aw_size_delay),
    .mem_1_aw_bits_len(mem_1_aw_len_delay),

    .mem_1_w_valid(mem_1_w_valid_delay),
    .mem_1_w_ready(mem_1_w_ready_delay),
    .mem_1_w_bits_strb(mem_1_w_strb_delay),
    .mem_1_w_bits_data(mem_1_w_data_delay),
    .mem_1_w_bits_last(mem_1_w_last_delay),

    .mem_1_r_valid(mem_1_r_valid_delay),
    .mem_1_r_ready(mem_1_r_ready_delay),
    .mem_1_r_bits_resp(mem_1_r_resp_delay),
    .mem_1_r_bits_id(mem_1_r_id_delay),
    .mem_1_r_bits_data(mem_1_r_data_delay),
    .mem_1_r_bits_last(mem_1_r_last_delay),

    .mem_1_b_valid(mem_1_b_valid_delay),
    .mem_1_b_ready(mem_1_b_ready_delay),
    .mem_1_b_bits_resp(mem_1_b_resp_delay),
    .mem_1_b_bits_id(mem_1_b_id_delay),
`endif
`ifdef MEM_HAS_CHANNEL2
    .mem_2_ar_valid(mem_2_ar_valid_delay),
    .mem_2_ar_ready(mem_2_ar_ready_delay),
    .mem_2_ar_bits_addr(mem_2_ar_addr_delay),
    .mem_2_ar_bits_id(mem_2_ar_id_delay),
    .mem_2_ar_bits_size(mem_2_ar_size_delay),
    .mem_2_ar_bits_len(mem_2_ar_len_delay),

    .mem_2_aw_valid(mem_2_aw_valid_delay),
    .mem_2_aw_ready(mem_2_aw_ready_delay),
    .mem_2_aw_bits_addr(mem_2_aw_addr_delay),
    .mem_2_aw_bits_id(mem_2_aw_id_delay),
    .mem_2_aw_bits_size(mem_2_aw_size_delay),
    .mem_2_aw_bits_len(mem_2_aw_len_delay),

    .mem_2_w_valid(mem_2_w_valid_delay),
    .mem_2_w_ready(mem_2_w_ready_delay),
    .mem_2_w_bits_strb(mem_2_w_strb_delay),
    .mem_2_w_bits_data(mem_2_w_data_delay),
    .mem_2_w_bits_last(mem_2_w_last_delay),

    .mem_2_r_valid(mem_2_r_valid_delay),
    .mem_2_r_ready(mem_2_r_ready_delay),
    .mem_2_r_bits_resp(mem_2_r_resp_delay),
    .mem_2_r_bits_id(mem_2_r_id_delay),
    .mem_2_r_bits_data(mem_2_r_data_delay),
    .mem_2_r_bits_last(mem_2_r_last_delay),

    .mem_2_b_valid(mem_2_b_valid_delay),
    .mem_2_b_ready(mem_2_b_ready_delay),
    .mem_2_b_bits_resp(mem_2_b_resp_delay),
    .mem_2_b_bits_id(mem_2_b_id_delay),
`endif
`ifdef MEM_HAS_CHANNEL3
    .mem_3_ar_valid(mem_3_ar_valid_delay),
    .mem_3_ar_ready(mem_3_ar_ready_delay),
    .mem_3_ar_bits_addr(mem_3_ar_addr_delay),
    .mem_3_ar_bits_id(mem_3_ar_id_delay),
    .mem_3_ar_bits_size(mem_3_ar_size_delay),
    .mem_3_ar_bits_len(mem_3_ar_len_delay),

    .mem_3_aw_valid(mem_3_aw_valid_delay),
    .mem_3_aw_ready(mem_3_aw_ready_delay),
    .mem_3_aw_bits_addr(mem_3_aw_addr_delay),
    .mem_3_aw_bits_id(mem_3_aw_id_delay),
    .mem_3_aw_bits_size(mem_3_aw_size_delay),
    .mem_3_aw_bits_len(mem_3_aw_len_delay),

    .mem_3_w_valid(mem_3_w_valid_delay),
    .mem_3_w_ready(mem_3_w_ready_delay),
    .mem_3_w_bits_strb(mem_3_w_strb_delay),
    .mem_3_w_bits_data(mem_3_w_data_delay),
    .mem_3_w_bits_last(mem_3_w_last_delay),

    .mem_3_r_valid(mem_3_r_valid_delay),
    .mem_3_r_ready(mem_3_r_ready_delay),
    .mem_3_r_bits_resp(mem_3_r_resp_delay),
    .mem_3_r_bits_id(mem_3_r_id_delay),
    .mem_3_r_bits_data(mem_3_r_data_delay),
    .mem_3_r_bits_last(mem_3_r_last_delay),

    .mem_3_b_valid(mem_3_b_valid_delay),
    .mem_3_b_ready(mem_3_b_ready_delay),
    .mem_3_b_bits_resp(mem_3_b_resp_delay),
    .mem_3_b_bits_id(mem_3_b_id_delay),
`endif
    .clock(clock),
    .reset(reset_delay)
  );

  always @(posedge clock) begin
    trace_count = trace_count + 1;
    tick(
      reset,
      fin,

      ctrl_ar_valid,
      ctrl_ar_ready,
      ctrl_ar_addr,
      ctrl_ar_id,
      ctrl_ar_size,
      ctrl_ar_len,

      ctrl_aw_valid,
      ctrl_aw_ready,
      ctrl_aw_addr,
      ctrl_aw_id,
      ctrl_aw_size,
      ctrl_aw_len,

      ctrl_w_valid,
      ctrl_w_ready,
      ctrl_w_strb,
      ctrl_w_data,
      ctrl_w_last,

      ctrl_r_valid,
      ctrl_r_ready,
      ctrl_r_resp,
      ctrl_r_id,
      ctrl_r_data,
      ctrl_r_last,

      ctrl_b_valid,
      ctrl_b_ready,
      ctrl_b_resp,
      ctrl_b_id,

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

      mem_0_ar_valid,
      mem_0_ar_ready,
      mem_0_ar_addr,
      mem_0_ar_id,
      mem_0_ar_size,
      mem_0_ar_len,

      mem_0_aw_valid,
      mem_0_aw_ready,
      mem_0_aw_addr,
      mem_0_aw_id,
      mem_0_aw_size,
      mem_0_aw_len,

      mem_0_w_valid,
      mem_0_w_ready,
      mem_0_w_strb,
      mem_0_w_data,
      mem_0_w_last,

      mem_0_r_valid,
      mem_0_r_ready,
      mem_0_r_resp,
      mem_0_r_id,
      mem_0_r_data,
      mem_0_r_last,

      mem_0_b_valid,
      mem_0_b_ready,
      mem_0_b_resp,
      mem_0_b_id,

      mem_1_ar_valid,
      mem_1_ar_ready,
      mem_1_ar_addr,
      mem_1_ar_id,
      mem_1_ar_size,
      mem_1_ar_len,

      mem_1_aw_valid,
      mem_1_aw_ready,
      mem_1_aw_addr,
      mem_1_aw_id,
      mem_1_aw_size,
      mem_1_aw_len,

      mem_1_w_valid,
      mem_1_w_ready,
      mem_1_w_strb,
      mem_1_w_data,
      mem_1_w_last,

      mem_1_r_valid,
      mem_1_r_ready,
      mem_1_r_resp,
      mem_1_r_id,
      mem_1_r_data,
      mem_1_r_last,

      mem_1_b_valid,
      mem_1_b_ready,
      mem_1_b_resp,
      mem_1_b_id,

      mem_2_ar_valid,
      mem_2_ar_ready,
      mem_2_ar_addr,
      mem_2_ar_id,
      mem_2_ar_size,
      mem_2_ar_len,

      mem_2_aw_valid,
      mem_2_aw_ready,
      mem_2_aw_addr,
      mem_2_aw_id,
      mem_2_aw_size,
      mem_2_aw_len,

      mem_2_w_valid,
      mem_2_w_ready,
      mem_2_w_strb,
      mem_2_w_data,
      mem_2_w_last,

      mem_2_r_valid,
      mem_2_r_ready,
      mem_2_r_resp,
      mem_2_r_id,
      mem_2_r_data,
      mem_2_r_last,

      mem_2_b_valid,
      mem_2_b_ready,
      mem_2_b_resp,
      mem_2_b_id,

      mem_3_ar_valid,
      mem_3_ar_ready,
      mem_3_ar_addr,
      mem_3_ar_id,
      mem_3_ar_size,
      mem_3_ar_len,

      mem_3_aw_valid,
      mem_3_aw_ready,
      mem_3_aw_addr,
      mem_3_aw_id,
      mem_3_aw_size,
      mem_3_aw_len,

      mem_3_w_valid,
      mem_3_w_ready,
      mem_3_w_strb,
      mem_3_w_data,
      mem_3_w_last,

      mem_3_r_valid,
      mem_3_r_ready,
      mem_3_r_resp,
      mem_3_r_id,
      mem_3_r_data,
      mem_3_r_last,

      mem_3_b_valid,
      mem_3_b_ready,
      mem_3_b_resp,
      mem_3_b_id
    );
  end
endmodule;
