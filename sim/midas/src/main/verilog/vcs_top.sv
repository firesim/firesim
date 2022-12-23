/* verilator lint_off PINMISSING */
/* verilator lint_off LITENDIAN */

`define AXI4_FWD_STRUCT(name, defname) \
  typedef struct packed {                                               \
    bit                              ar_ready;                          \
    bit                              aw_ready;                          \
    bit                              w_ready;                           \
    bit                              r_valid;                           \
    bit [1:0]                        r_resp;                            \
    bit [```defname``_ID_BITS-1:0]   r_id;                              \
    bit [```defname``_DATA_BITS-1:0] r_data;                            \
    bit                              r_last;                            \
    bit                              b_valid;                           \
    bit [1:0]                        b_resp;                            \
    bit [```defname``_ID_BITS-1:0]   b_id;                              \
  } name``_fwd_t;                                                       \
  typedef struct packed {                                               \
    bit                              ar_valid;                          \
    bit [```defname``_ADDR_BITS-1:0] ar_addr;                           \
    bit [```defname``_ID_BITS-1:0]   ar_id;                             \
    bit [2:0]                        ar_size;                           \
    bit [7:0]                        ar_len;                            \
    bit                              aw_valid;                          \
    bit [```defname``_ADDR_BITS-1:0] aw_addr;                           \
    bit [```defname``_ID_BITS-1:0]   aw_id;                             \
    bit [2:0]                        aw_size;                           \
    bit [7:0]                        aw_len;                            \
    bit                              w_valid;                           \
    bit [```defname``_STRB_BITS-1:0] w_strb;                            \
    bit [```defname``_DATA_BITS-1:0] w_data;                            \
    bit                              w_last;                            \
    bit                              r_ready;                           \
    bit                              b_ready;                           \
  } name``_rev_t;

`AXI4_FWD_STRUCT(ctrl, CTRL)
`AXI4_FWD_STRUCT(cpu_managed_axi4, CPU_MANAGED_AXI4)
`AXI4_FWD_STRUCT(fpga_managed_axi4, FPGA_MANAGED_AXI4)
`AXI4_FWD_STRUCT(mem, MEM)

`define BIND_AXI_FWD(name, obj)                    \
    .name``_ar_ready(obj.ar_ready),                \
    .name``_aw_ready(obj.aw_ready),                \
    .name``_w_ready(obj.w_ready),                  \
    .name``_r_valid(obj.r_valid),                  \
    .name``_r_bits_resp(obj.r_resp),               \
    .name``_r_bits_id(obj.r_id),                   \
    .name``_r_bits_data(obj.r_data),               \
    .name``_r_bits_last(obj.r_last),               \
    .name``_b_valid(obj.b_valid),                  \
    .name``_b_bits_resp(obj.b_resp),               \
    .name``_b_bits_id(obj.b_id),

`define BIND_AXI_REV(name, obj)                    \
    .name``_ar_valid(obj.ar_valid),                \
    .name``_ar_bits_addr(obj.ar_addr),             \
    .name``_ar_bits_id(obj.ar_id),                 \
    .name``_ar_bits_size(obj.ar_size),             \
    .name``_ar_bits_len(obj.ar_len),               \
    .name``_aw_valid(obj.aw_valid),                \
    .name``_aw_bits_addr(obj.aw_addr),             \
    .name``_aw_bits_id(obj.aw_id),                 \
    .name``_aw_bits_size(obj.aw_size),             \
    .name``_aw_bits_len(obj.aw_len),               \
    .name``_w_valid(obj.w_valid),                  \
    .name``_w_bits_strb(obj.w_strb),               \
    .name``_w_bits_data(obj.w_data),               \
    .name``_w_bits_last(obj.w_last),               \
    .name``_r_ready(obj.r_ready),                  \
    .name``_b_ready(obj.b_ready),

`define BIND_CHANNEL(name)                \
    `BIND_AXI_FWD(name, name``_fwd_delay) \
    `BIND_AXI_REV(name, name``_rev_delay)

import "DPI-C" function void tick
(
  output bit                                          reset,
  output bit                                          fin,

  output ctrl_rev_t                                   ctrl_rev,
  input  ctrl_fwd_t                                   ctrl_fwd,

  output cpu_managed_axi4_rev_t                       cpu_managed_axi4_rev,
  input  cpu_managed_axi4_fwd_t                       cpu_managed_axi4_fwd,

  input  fpga_managed_axi4_rev_t                      fpga_managed_axi4_rev,
  output fpga_managed_axi4_fwd_t                      fpga_managed_axi4_fwd,

  input  mem_rev_t                                    mem_0_rev,
  output mem_fwd_t                                    mem_0_fwd,
  input  mem_rev_t                                    mem_1_rev,
  output mem_fwd_t                                    mem_1_fwd,
  input  mem_rev_t                                    mem_2_rev,
  output mem_fwd_t                                    mem_2_fwd,
  input  mem_rev_t                                    mem_3_rev,
  output mem_fwd_t                                    mem_3_fwd
);

module emul(
`ifdef VERILATOR
  input bit clock,
  input bit pre_clock,
  input bit post_clock
`endif
);

`ifndef VERILATOR
  // Chisel-generated Verilog might contain race conditions. To address
  // problems, information is passed to and from the design from the DPI call
  // with a slight delay using out-of-phase clocks. The design is not allowed
  // to make assumptions about these clocks and they are not exposed to it.
  bit clock;
  initial clock = 1'b0;
  always clock = #(`CLOCK_PERIOD / 2.0) ~clock;

  bit pre_clock;
  initial begin
    pre_clock = 1'b0;
    #(`CLOCK_PERIOD / 2.0 - 0.1) forever
      pre_clock = #(`CLOCK_PERIOD / 2.0) ~pre_clock;
  end

  bit post_clock;
  initial begin
    post_clock = 1'b0;
    #(`CLOCK_PERIOD / 2.0 + 0.1) forever
      post_clock = #(`CLOCK_PERIOD / 2.0) ~post_clock;
  end
`endif

  reg reset = 1'b1;
  reg fin = 1'b0;

`ifndef VERILATOR
`ifdef DEBUG
  reg [2047:0] vcdplusfile = 2048'h0;
  reg [63:0] dump_start = 64'h0;
  reg [63:0] dump_end = {64{1'b1}};
  reg [63:0] dump_cycles = 64'h0;
  reg [63:0] trace_count = 64'h0;

  initial begin
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
  end
`endif
`endif

  ctrl_fwd_t ctrl_fwd;
  ctrl_rev_t ctrl_rev;
  cpu_managed_axi4_fwd_t cpu_managed_axi4_fwd;
  cpu_managed_axi4_rev_t cpu_managed_axi4_rev;
  fpga_managed_axi4_fwd_t fpga_managed_axi4_fwd;
  fpga_managed_axi4_rev_t fpga_managed_axi4_rev;
  mem_fwd_t mem_0_fwd;
  mem_rev_t mem_0_rev;
  mem_fwd_t mem_1_fwd;
  mem_rev_t mem_1_rev;
  mem_fwd_t mem_2_fwd;
  mem_rev_t mem_2_rev;
  mem_fwd_t mem_3_fwd;
  mem_rev_t mem_3_rev;

  ctrl_fwd_t ctrl_fwd_delay;
  ctrl_rev_t ctrl_rev_delay;
  cpu_managed_axi4_fwd_t cpu_managed_axi4_fwd_delay;
  cpu_managed_axi4_rev_t cpu_managed_axi4_rev_delay;
  fpga_managed_axi4_rev_t fpga_managed_axi4_rev_delay;
  fpga_managed_axi4_fwd_t fpga_managed_axi4_fwd_delay;
  mem_fwd_t mem_0_fwd_delay;
  mem_rev_t mem_0_rev_delay;
  mem_fwd_t mem_1_fwd_delay;
  mem_rev_t mem_1_rev_delay;
  mem_fwd_t mem_2_fwd_delay;
  mem_rev_t mem_2_rev_delay;
  mem_fwd_t mem_3_fwd_delay;
  mem_rev_t mem_3_rev_delay;

  bit reset_delay;

  always_ff @(posedge pre_clock) begin
    ctrl_fwd = ctrl_fwd_delay;
    cpu_managed_axi4_fwd = cpu_managed_axi4_fwd_delay;
    fpga_managed_axi4_rev = fpga_managed_axi4_rev_delay;
    mem_0_rev = mem_0_rev_delay;
    mem_1_rev = mem_1_rev_delay;
    mem_2_rev = mem_2_rev_delay;
    mem_3_rev = mem_3_rev_delay;
  end

  always_ff @(posedge post_clock) begin
    ctrl_rev_delay = ctrl_rev;
    cpu_managed_axi4_rev_delay = cpu_managed_axi4_rev;
    fpga_managed_axi4_fwd_delay = fpga_managed_axi4_fwd;
    mem_0_fwd_delay = mem_0_fwd;
    mem_1_fwd_delay = mem_1_fwd;
    mem_2_fwd_delay = mem_2_fwd;
    mem_3_fwd_delay = mem_3_fwd;
    reset_delay = reset;
  end

  FPGATop FPGATop(
`ifdef CPU_MANAGED_AXI4_PRESENT
    `BIND_CHANNEL(cpu_managed_axi4)
`endif
`ifdef FPGA_MANAGED_AXI4_PRESENT
    `BIND_CHANNEL(fpga_managed_axi4)
`endif

    `BIND_CHANNEL(ctrl)

    `BIND_CHANNEL(mem_0)
`ifdef MEM_HAS_CHANNEL1
    `BIND_CHANNEL(mem_1)
`endif
`ifdef MEM_HAS_CHANNEL2
    `BIND_CHANNEL(mem_2)
`endif
`ifdef MEM_HAS_CHANNEL3
    `BIND_CHANNEL(mem_3)
`endif
    .clock(clock),
    .reset(reset_delay)
  );

  always @(posedge clock) begin
    tick(
      reset,
      fin,
      ctrl_rev, ctrl_fwd,
      cpu_managed_axi4_rev, cpu_managed_axi4_fwd,
      fpga_managed_axi4_rev, fpga_managed_axi4_fwd,
      mem_0_rev, mem_0_fwd,
      mem_1_rev, mem_1_fwd,
      mem_2_rev, mem_2_fwd,
      mem_3_rev, mem_3_fwd
    );
`ifdef DEBUG
    trace_count = trace_count + 1;
`endif
  end
endmodule;
