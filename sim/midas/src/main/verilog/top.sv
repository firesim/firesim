/* verilator lint_off LITENDIAN */

`ifndef CPU_MANAGED_AXI4_ADDR_BITS
`define CPU_MANAGED_AXI4_ADDR_BITS 0
`define CPU_MANAGED_AXI4_DATA_BITS 0
`define CPU_MANAGED_AXI4_ID_BITS 0
`endif

`ifndef FPGA_MANAGED_AXI4_ADDR_BITS
`define FPGA_MANAGED_AXI4_ADDR_BITS 0
`define FPGA_MANAGED_AXI4_DATA_BITS 0
`define FPGA_MANAGED_AXI4_ID_BITS 0
`endif

`define AXI4_STRUCT(name, defname) \
  typedef struct packed {                                                 \
    bit                                ar_ready;                          \
    bit                                aw_ready;                          \
    bit                                w_ready;                           \
    bit                                r_valid;                           \
    bit [1:0]                          r_resp;                            \
    bit [```defname``_ID_BITS-1:0]     r_id;                              \
    bit [```defname``_DATA_BITS-1:0]   r_data;                            \
    bit                                r_last;                            \
    bit                                b_valid;                           \
    bit [1:0]                          b_resp;                            \
    bit [```defname``_ID_BITS-1:0]     b_id;                              \
  } name``_fwd_t;                                                         \
  typedef struct packed {                                                 \
    bit                                ar_valid;                          \
    bit [```defname``_ADDR_BITS-1:0]   ar_addr;                           \
    bit [```defname``_ID_BITS-1:0]     ar_id;                             \
    bit [2:0]                          ar_size;                           \
    bit [7:0]                          ar_len;                            \
    bit                                aw_valid;                          \
    bit [```defname``_ADDR_BITS-1:0]   aw_addr;                           \
    bit [```defname``_ID_BITS-1:0]     aw_id;                             \
    bit [2:0]                          aw_size;                           \
    bit [7:0]                          aw_len;                            \
    bit                                w_valid;                           \
    bit [```defname``_DATA_BITS/8-1:0] w_strb;                            \
    bit [```defname``_DATA_BITS-1:0]   w_data;                            \
    bit                                w_last;                            \
    bit                                r_ready;                           \
    bit                                b_ready;                           \
  } name``_rev_t;

`AXI4_STRUCT(ctrl, CTRL)
`AXI4_STRUCT(mem, MEM)
`AXI4_STRUCT(cpu_managed_axi4, CPU_MANAGED_AXI4)
`AXI4_STRUCT(fpga_managed_axi4, FPGA_MANAGED_AXI4)

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

`define BIND_CHANNEL(name)          \
    `BIND_AXI_FWD(name, name``_fwd) \
    `BIND_AXI_REV(name, name``_rev)

import "DPI-C" function void simulator_tick
(
  input  bit                                          reset,
  output bit                                          fin,

  input  ctrl_fwd_t                                   ctrl_fwd,
  input  cpu_managed_axi4_fwd_t                       cpu_managed_axi4_fwd,
  input  fpga_managed_axi4_rev_t                      fpga_managed_axi4_rev,
  input  mem_rev_t                                    mem_0_rev,
  input  mem_rev_t                                    mem_1_rev,
  input  mem_rev_t                                    mem_2_rev,
  input  mem_rev_t                                    mem_3_rev,

  output ctrl_rev_t                                   ctrl_rev,
  output cpu_managed_axi4_rev_t                       cpu_managed_axi4_rev,
  output fpga_managed_axi4_fwd_t                      fpga_managed_axi4_fwd,
  output mem_fwd_t                                    mem_0_fwd,
  output mem_fwd_t                                    mem_1_fwd,
  output mem_fwd_t                                    mem_2_fwd,
  output mem_fwd_t                                    mem_3_fwd
);

import "DPI-C" function void simulator_entry();

module emul(
);
  // Generate a single clock signal as long as the simulation is running.
  bit clock = 1'b0;
  bit fin = 1'b0;
  bit reset = 1'b1;
  initial begin
    while (!fin) begin
      clock = #(`CLOCK_PERIOD * 1000 / 2.0) ~clock;
    end
  end

  initial begin
    // Wait for the Vivado internal reset signal for gate-level sims.
    `ifdef GATE_LEVEL_SIM
    reset = 1'b0;
    repeat (2) @(posedge clock); #0.001
    reset = 1'b1;
    while (glbl.GSR) @(posedge clock);
    `endif

    // Maintain 10 reset cycles.
    #(`CLOCK_PERIOD * 1000 * 9.0) reset = 1'b0;
  end

`ifdef DEBUG
  reg [2047:0] vcdplusfile = 2048'h0;
  reg [2047:0] fsdbfile = 2048'h0;
  reg [2047:0] waveformfile = 2048'h0;
  reg [63:0] dump_start = 64'h0;
  reg [63:0] dump_end = {64{1'b1}};
  reg [63:0] dump_cycles = 64'h0;
  reg [63:0] trace_count = 64'h0;
`endif // DEBUG
  integer stderr = 32'h80000002;

  initial begin
`ifdef DEBUG

    if ($value$plusargs("vcdplusfile=%s", vcdplusfile)) begin
`ifdef VCS
      $vcdplusfile(vcdplusfile);
`else // VCS
      $fdisplay(stderr, "Error: +vcdplusfile is VCS-only; use +waveformfile instead or recompile with VCS=1");
      $fatal;
`endif // VCS
    end

    if ($value$plusargs("fsdbfile=%s", fsdbfile)) begin
`ifdef FSDB
      $fsdbDumpfile(fsdbfile);
      $fsdbDumpvars("+all");
      //$fsdbDumpSVA;
`else // FSDB
      $fdisplay(stderr, "Error: +fsdbfile is FSDB-only; use +waveformfile/+vcdplusfile instead or recompile with FSDB=1");
      $fatal;
`endif // FSDB
    end

    if ($value$plusargs("waveformfile=%s", waveformfile))
    begin
      $dumpfile(waveformfile);
      $dumpvars(0, FPGATop);
    end

`ifdef FSDB
`define WAVEFORMON $fsdbDumpon;
`define WAVEFORMCLOSE $fsdbDumpoff;
`elsif VCS
`define WAVEFORMON $vcdpluson(0); $vcdplusmemon(0);
`define WAVEFORMCLOSE $vcdplusclose; $dumpoff;
`else // FSDB/VCS
`define WAVEFORMON $dumpon;
`define WAVEFORMCLOSE $dumpoff;
`endif // FSDB/VCS

    void'($value$plusargs("dump-start=%d", dump_start));
    void'($value$plusargs("max-cycles=%d", dump_cycles));
    dump_end = dump_start + dump_cycles;

    wait (trace_count >= dump_start) begin
      `WAVEFORMON
    end
    wait ((trace_count > dump_end) || fin) begin
      `WAVEFORMCLOSE
    end

`else // DEBUG

    if ($test$plusargs("vcdplusfile=") || $test$plusargs("waveformfile=") || $test$plusargs("fsdbfile="))
    begin
      $fdisplay(stderr, "Error: +waveformfile, +vcdplusfile, or +fsdbfile requested but compile did not have +define+DEBUG enabled");
      $fatal;
    end

`endif // DEBUG
  end

  initial begin
    simulator_entry();
  end

  // Bind the top-level to the records carrying information in and out of it.
  ctrl_rev_t ctrl_rev;
  cpu_managed_axi4_rev_t cpu_managed_axi4_rev;
  fpga_managed_axi4_fwd_t fpga_managed_axi4_fwd;
  mem_fwd_t mem_0_fwd;
  mem_fwd_t mem_1_fwd;
  mem_fwd_t mem_2_fwd;
  mem_fwd_t mem_3_fwd;

  ctrl_fwd_t ctrl_fwd;
  cpu_managed_axi4_fwd_t cpu_managed_axi4_fwd;
  fpga_managed_axi4_rev_t fpga_managed_axi4_rev;
  mem_rev_t mem_0_rev;
  mem_rev_t mem_1_rev;
  mem_rev_t mem_2_rev;
  mem_rev_t mem_3_rev;

  /* verilator lint_off PINMISSING */
  FPGATop FPGATop(
    `BIND_CHANNEL(ctrl)
`ifdef CPU_MANAGED_AXI4_PRESENT
    `BIND_CHANNEL(cpu_managed_axi4)
`endif
`ifdef FPGA_MANAGED_AXI4_PRESENT
    `BIND_CHANNEL(fpga_managed_axi4)
`endif
`ifdef MEM_HAS_CHANNEL0
    `BIND_CHANNEL(mem_0)
`endif
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
    .reset(reset)
  );
  /* verilator lint_on PINMISSING */

  // Bind the simulation to another bank of records.
  ctrl_fwd_t ctrl_fwd_sync;
  cpu_managed_axi4_fwd_t cpu_managed_axi4_fwd_sync;
  fpga_managed_axi4_rev_t fpga_managed_axi4_rev_sync;
  mem_rev_t mem_0_rev_sync;
  mem_rev_t mem_1_rev_sync;
  mem_rev_t mem_2_rev_sync;
  mem_rev_t mem_3_rev_sync;

  ctrl_rev_t ctrl_rev_sync;
  cpu_managed_axi4_rev_t cpu_managed_axi4_rev_sync;
  fpga_managed_axi4_fwd_t fpga_managed_axi4_fwd_sync;
  mem_fwd_t mem_0_fwd_sync;
  mem_fwd_t mem_1_fwd_sync;
  mem_fwd_t mem_2_fwd_sync;
  mem_fwd_t mem_3_fwd_sync;

  // To deal with the race conditions in Chisel-generated SystemVerilog, the
  // simulator inputs are latched out-of-phase, on the negative edge.
  // The `simulator_tick` function is delayed by one cycle relative to the DUT.
  // When the DUT is on cycle N, the tick function uses the inputs of the N-1th
  // cycle to compute the output for the Nth cycle. These outputs are delayed
  // by one cycle so on the N+1st cycle the DUT computes the N+1st cycle using
  // inputs from the Nth cycle.
  always @(posedge clock) begin
    simulator_tick(
      reset,
      fin,

      ctrl_fwd_sync,
      cpu_managed_axi4_fwd_sync,
      fpga_managed_axi4_rev_sync,
      mem_0_rev_sync,
      mem_1_rev_sync,
      mem_2_rev_sync,
      mem_3_rev_sync,

      ctrl_rev_sync,
      cpu_managed_axi4_rev_sync,
      fpga_managed_axi4_fwd_sync,
      mem_0_fwd_sync,
      mem_1_fwd_sync,
      mem_2_fwd_sync,
      mem_3_fwd_sync
    );


`ifdef DEBUG
    /* verilator lint_off BLKSEQ */
    trace_count = trace_count + 1;
    /* verilator lint_on BLKSEQ */
`endif
  end

  always_ff @(negedge clock) begin
    ctrl_fwd_sync <= ctrl_fwd;
    cpu_managed_axi4_fwd_sync <= cpu_managed_axi4_fwd;
    fpga_managed_axi4_rev_sync <= fpga_managed_axi4_rev;
    mem_0_rev_sync <= mem_0_rev;
    mem_1_rev_sync <= mem_1_rev;
    mem_2_rev_sync <= mem_2_rev;
    mem_3_rev_sync <= mem_3_rev;
  end

  always_ff @(posedge clock) begin
    ctrl_rev <= ctrl_rev_sync;
    cpu_managed_axi4_rev <= cpu_managed_axi4_rev_sync;
    fpga_managed_axi4_fwd <= fpga_managed_axi4_fwd_sync;
    mem_0_fwd <= mem_0_fwd_sync;
    mem_1_fwd <= mem_1_fwd_sync;
    mem_2_fwd <= mem_2_fwd_sync;
    mem_3_fwd <= mem_3_fwd_sync;
  end
endmodule;
