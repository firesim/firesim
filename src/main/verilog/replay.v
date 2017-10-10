// See LICENSE for license details.

module replay;
  reg clock = 1'b0;
  reg reset = 1'b1;
  reg exit = 1'b0;
  reg [64:0] cycles = 0;

`ifdef VCS 
  always #(`CLOCK_PERIOD / 2.0) clock = ~clock;

  reg vcdon = 1'b0;
  reg [1023:0] vcdfile = 0;
  reg [1023:0] vcdplusfile = 0;
`endif

/* include compiler generated testbench fragment */
`include `VFRAG

  initial begin
`ifdef VCS
    if ($value$plusargs("vcdfile=%s", vcdfile))
    begin
      $dumpfile(vcdfile);
      $dumpvars(0, `TOP_TYPE);
      $dumpoff;
    end
    if ($value$plusargs("waveform=%s", vcdplusfile))
    begin
      $vcdplusfile(vcdplusfile);
    end
`endif
    $init_sigs(`TOP_TYPE);
  end

  always @(posedge clock) begin
    if (!reset) begin
      cycles <= cycles + 1;
      if ((vcdfile || vcdplusfile) && !vcdon) begin
        if (vcdfile) begin
          $dumpon;
        end
`ifdef VCS
        if (vcdplusfile) begin
          $vcdpluson(0);
          $vcdplusmemon(0);
        end
`endif
        vcdon <= 1;
      end
    end
  end

  always @(negedge clock) begin
    $tick(exit);
    if (exit) begin
      if (vcdfile && vcdon) begin
`ifdef VCS
        // FIXME: Why not $dumpoff?
        // (vcs K-2015.09-SP2-1, L-2016.06-1)
        $dumpon;
`else
        $dumpoff;
`endif
      end
`ifdef VCS
      if (vcdplusfile) begin
        $vcdplusclose;
      end
`endif
      $finish;
    end
  end

endmodule
