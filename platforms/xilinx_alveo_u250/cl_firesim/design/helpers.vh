`ifndef HELPERS_VH
`define HELPERS_VH

`define DDR4_PDEF(PNAME) \
  output wire        ``PNAME``_act_n, \
  output wire [16:0] ``PNAME``_adr, \
  output wire [1:0]  ``PNAME``_ba, \
  output wire [1:0]  ``PNAME``_bg, \
  output wire        ``PNAME``_ck_c, \
  output wire        ``PNAME``_ck_t, \
  output wire        ``PNAME``_cke, \
  output wire        ``PNAME``_cs_n, \
  inout  wire [71:0] ``PNAME``_dq, \
  inout  wire [17:0] ``PNAME``_dqs_c, \
  inout  wire [17:0] ``PNAME``_dqs_t, \
  output wire        ``PNAME``_odt, \
  output wire        ``PNAME``_par, \
  output wire        ``PNAME``_reset_n

//-----------------------------------------------------------
// Must define/override the following...
// i.e.
//   `define DDR4_PAR // use par instead of parity
//-----------------------------------------------------------

`define DDR4_CONNECT(PNAME, WNAME) \
  , .``PNAME``_act_n(``WNAME``_act_n) \
  , .``PNAME``_adr(``WNAME``_adr) \
  , .``PNAME``_ba(``WNAME``_ba) \
  , .``PNAME``_bg(``WNAME``_bg) \
  , .``PNAME``_ck_c(``WNAME``_ck_c) \
  , .``PNAME``_ck_t(``WNAME``_ck_t) \
  , .``PNAME``_cke(``WNAME``_cke) \
  , .``PNAME``_cs_n(``WNAME``_cs_n) \
  , .``PNAME``_dq(``WNAME``_dq) \
  , .``PNAME``_dqs_c(``WNAME``_dqs_c) \
  , .``PNAME``_dqs_t(``WNAME``_dqs_t) \
  , .``PNAME``_odt(``WNAME``_odt) \
`ifdef DDR4_PAR \
  , .``PNAME``_par(``WNAME``_par) \
`else \
  , .``PNAME``_parity(``WNAME``_par) \
`endif \
  , .``PNAME``_reset_n(``WNAME``_reset_n)

// ------------------

`define DIFF_CLK_PDEF(PNAME) \
  input wire ``PNAME``_clk_n, \
  input wire ``PNAME``_clk_p

`define DIFF_CLK_CONNECT(PNAME, WNAME) \
  , .``PNAME``_clk_n(``WNAME``_clk_n) \
  , .``PNAME``_clk_p(``WNAME``_clk_p)

`endif // HELPERS_VH
