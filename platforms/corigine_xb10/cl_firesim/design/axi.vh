`ifndef AXI_VH
`define AXI_VH

//-----------------------------------------------------------
// Must define/override the following...
// i.e.
//   `define Otype wire // i/o signal type
//   `define Itype wire
//   `define AMBA_AXI4 // use AXI4 or AXI4_LITE
//   `define AMBA_AXI_CACHE // has *cache
//   `define AMBA_AXI_PROT // has *prot
//   `define AMBA_AXI_REGION // has *region
//   `define AMBA_AXI_QOS // has *qos
//   `define AMBA_AXI_ID // has *id
//-----------------------------------------------------------

// can override
`define Otype wire
`define Itype wire

`define AMBA_AXI_MASTER_PORT_AW(PNAME, ID, AD) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , output `Otype [``ID``-1:0]        ``PNAME``_awid \
    `endif \
    `endif \
    , output `Otype [``AD``-1:0]        ``PNAME``_awaddr \
    `ifdef AMBA_AXI4 \
    , output `Otype [ 7:0]              ``PNAME``_awlen \
    , output `Otype                     ``PNAME``_awlock \
    , output `Otype [ 2:0]              ``PNAME``_awsize \
    , output `Otype [ 1:0]              ``PNAME``_awburst \
    `ifdef AMBA_AXI_CACHE \
    , output `Otype [ 3:0]              ``PNAME``_awcache \
    `endif \
    `endif \
    `ifdef AMBA_AXI_PROT \
    , output `Otype [ 2:0]              ``PNAME``_awprot \
    `endif \
    , output `Otype                     ``PNAME``_awvalid \
    , input  `Itype                     ``PNAME``_awready \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_QOS \
    , output `Otype [ 3:0]              ``PNAME``_awqos \
    `endif \
    `ifdef AMBA_AXI_REGION \
    , output `Otype [ 3:0]              ``PNAME``_awregion \
    `endif \
    `endif

`define AMBA_AXI_MASTER_PORT_W(PNAME, ID, DA) \
    , output `Otype [``DA``-1:0]        ``PNAME``_wdata \
    , output `Otype [(``DA``/8)-1:0]        ``PNAME``_wstrb \
    `ifdef AMBA_AXI4 \
    , output `Otype                     ``PNAME``_wlast \
    `endif \
    , output `Otype                     ``PNAME``_wvalid \
    , input  `Itype                     ``PNAME``_wready

`define AMBA_AXI_MASTER_PORT_B(PNAME, ID) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , input  `Itype [``ID``-1:0]        ``PNAME``_bid \
    `endif \
    `endif \
    , input  `Itype [ 1:0]              ``PNAME``_bresp \
    , input  `Itype                     ``PNAME``_bvalid \
    , output `Otype                     ``PNAME``_bready

`define AMBA_AXI_MASTER_PORT_AR(PNAME, ID, AD) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , output `Otype [``ID``-1:0]        ``PNAME``_arid \
    `endif \
    `endif \
    , output `Otype [``AD``-1:0]        ``PNAME``_araddr \
    `ifdef AMBA_AXI4 \
    , output `Otype [ 7:0]              ``PNAME``_arlen \
    , output `Otype                     ``PNAME``_arlock \
    , output `Otype [ 2:0]              ``PNAME``_arsize \
    , output `Otype [ 1:0]              ``PNAME``_arburst \
    `ifdef AMBA_AXI_CACHE \
    , output `Otype [ 3:0]              ``PNAME``_arcache \
    `endif \
    `endif \
    `ifdef AMBA_AXI_PROT \
    , output `Otype [ 2:0]              ``PNAME``_arprot \
    `endif \
    , output `Otype                     ``PNAME``_arvalid \
    , input  `Itype                     ``PNAME``_arready \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_QOS \
    , output `Otype [ 3:0]              ``PNAME``_arqos \
    `endif \
    `ifdef AMBA_AXI_REGION \
    , output `Otype [ 3:0]              ``PNAME``_arregion \
    `endif \
    `endif

`define AMBA_AXI_MASTER_PORT_R(PNAME, ID, DA) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , input  `Itype [``ID``-1:0]        ``PNAME``_rid \
    `endif \
    `endif \
    , input  `Itype [``DA``-1:0]        ``PNAME``_rdata \
    , input  `Itype [ 1:0]              ``PNAME``_rresp \
    `ifdef AMBA_AXI4 \
    , input  `Itype                     ``PNAME``_rlast \
    `endif \
    , input  `Itype                     ``PNAME``_rvalid \
    , output `Otype                     ``PNAME``_rready

`define AMBA_AXI_MASTER_PORT(PNAME, ID, AD, DA) \
    `AMBA_AXI_MASTER_PORT_AW(``PNAME``, ``ID``, ``AD``) \
    `AMBA_AXI_MASTER_PORT_W(``PNAME``, ``ID``, ``DA``) \
    `AMBA_AXI_MASTER_PORT_B(``PNAME``, ``ID``) \
    `AMBA_AXI_MASTER_PORT_AR(``PNAME``, ``ID``, ``DA``) \
    `AMBA_AXI_MASTER_PORT_R(``PNAME``, ``ID``, ``DA``)

//-----------------------------------------------------------

`define AMBA_AXI_SLAVE_PORT_AW(PNAME, SID, AD) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , input  `Itype [``SID``-1:0]       ``PNAME``_awid \
    `endif \
    `endif \
    , input  `Itype [``AD``-1:0]        ``PNAME``_awaddr \
    `ifdef AMBA_AXI4 \
    , input  `Itype [ 7:0]              ``PNAME``_awlen \
    , input  `Itype                     ``PNAME``_awlock \
    , input  `Itype [ 2:0]              ``PNAME``_awsize \
    , input  `Itype [ 1:0]              ``PNAME``_awburst \
    `ifdef AMBA_AXI_CACHE \
    , input  `Itype [ 3:0]              ``PNAME``_awcache \
    `endif \
    `endif \
    `ifdef AMBA_AXI_PROT \
    , input  `Itype [ 2:0]              ``PNAME``_awprot \
    `endif \
    , input  `Itype                     ``PNAME``_awvalid \
    , output `Otype                     ``PNAME``_awready \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_QOS \
    , input  `Itype [ 3:0]              ``PNAME``_awqos \
    `endif \
    `ifdef AMBA_AXI_REGION \
    , input  `Itype [ 3:0]              ``PNAME``_awregion \
    `endif \
    `endif

`define AMBA_AXI_SLAVE_PORT_W(PNAME, SID, DA) \
    , input  `Itype [``DA``-1:0]        ``PNAME``_wdata \
    , input  `Itype [(``DA``/8)-1:0]        ``PNAME``_wstrb \
    `ifdef AMBA_AXI4 \
    , input  `Itype                     ``PNAME``_wlast \
    `endif \
    , input  `Itype                     ``PNAME``_wvalid \
    , output `Otype                     ``PNAME``_wready

`define AMBA_AXI_SLAVE_PORT_B(PNAME, SID) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , output `Otype [``SID``-1:0]       ``PNAME``_bid \
    `endif \
    `endif \
    , output `Otype [ 1:0]              ``PNAME``_bresp \
    , output `Otype                     ``PNAME``_bvalid \
    , input  `Itype                     ``PNAME``_bready

`define AMBA_AXI_SLAVE_PORT_AR(PNAME, SID, AD) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , input  `Itype [``SID``-1:0]       ``PNAME``_arid \
    `endif \
    `endif \
    , input  `Itype [``AD``-1:0]        ``PNAME``_araddr \
    `ifdef AMBA_AXI4 \
    , input  `Itype [ 7:0]              ``PNAME``_arlen \
    , input  `Itype                     ``PNAME``_arlock \
    , input  `Itype [ 2:0]              ``PNAME``_arsize \
    , input  `Itype [ 1:0]              ``PNAME``_arburst \
    `ifdef AMBA_AXI_CACHE \
    , input  `Itype [ 3:0]              ``PNAME``_arcache \
    `endif \
    `endif \
    `ifdef AMBA_AXI_PROT \
    , input  `Itype [ 2:0]              ``PNAME``_arprot \
    `endif \
    , input  `Itype                     ``PNAME``_arvalid \
    , output `Otype                     ``PNAME``_arready \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_QOS \
    , input  `Itype [ 3:0]              ``PNAME``_arqos \
    `endif \
    `ifdef AMBA_AXI_REGION \
    , input  `Itype [ 3:0]              ``PNAME``_arregion \
    `endif \
    `endif

`define AMBA_AXI_SLAVE_PORT_R(PNAME, SID, DA) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , output `Otype [``SID``-1:0]       ``PNAME``_rid \
    `endif \
    `endif \
    , output `Otype [``DA``-1:0]        ``PNAME``_rdata \
    , output `Otype [ 1:0]              ``PNAME``_rresp \
    `ifdef AMBA_AXI4 \
    , output `Otype                     ``PNAME``_rlast \
    `endif \
    , output `Otype                     ``PNAME``_rvalid \
    , input  `Itype                     ``PNAME``_rready

`define AMBA_AXI_SLAVE_PORT(PNAME, ID, AD, DA) \
    `AMBA_AXI_SLAVE_PORT_AW(``PNAME``, ``ID``, ``AD``) \
    `AMBA_AXI_SLAVE_PORT_W(``PNAME``, ``ID``, ``DA``) \
    `AMBA_AXI_SLAVE_PORT_B(``PNAME``, ``ID``) \
    `AMBA_AXI_SLAVE_PORT_AR(``PNAME``, ``ID``, ``AD``) \
    `AMBA_AXI_SLAVE_PORT_R(``PNAME``, ``ID``, ``DA``)

//-----------------------------------------------------

`define AMBA_AXI_PORT_CONNECTION_AW(PNAME, WNAME) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , .``PNAME``_awid     (``WNAME``_awid    ) \
    `endif \
    `endif \
    , .``PNAME``_awaddr   (``WNAME``_awaddr  ) \
    `ifdef AMBA_AXI4 \
    , .``PNAME``_awlen    (``WNAME``_awlen   ) \
    , .``PNAME``_awlock   (``WNAME``_awlock  ) \
    , .``PNAME``_awsize   (``WNAME``_awsize  ) \
    , .``PNAME``_awburst  (``WNAME``_awburst ) \
    `ifdef AMBA_AXI_CACHE \
    , .``PNAME``_awcache  (``WNAME``_awcache ) \
    `endif \
    `endif \
    `ifdef AMBA_AXI_PROT \
    , .``PNAME``_awprot   (``WNAME``_awprot  ) \
    `endif \
    , .``PNAME``_awvalid  (``WNAME``_awvalid ) \
    , .``PNAME``_awready  (``WNAME``_awready ) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_QOS \
    , .``PNAME``_awqos    (``WNAME``_awqos   ) \
    `endif \
    `ifdef AMBA_AXI_REGION \
    , .``PNAME``_awregion (``WNAME``_awregion) \
    `endif \
    `endif

`define AMBA_AXI_PORT_CONNECTION_W(PNAME, WNAME) \
    , .``PNAME``_wdata    (``WNAME``_wdata   ) \
    , .``PNAME``_wstrb    (``WNAME``_wstrb   ) \
    `ifdef AMBA_AXI4 \
    , .``PNAME``_wlast    (``WNAME``_wlast   ) \
    `endif \
    , .``PNAME``_wvalid   (``WNAME``_wvalid  ) \
    , .``PNAME``_wready   (``WNAME``_wready  )

`define AMBA_AXI_PORT_CONNECTION_B(PNAME, WNAME) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , .``PNAME``_bid      (``WNAME``_bid     ) \
    `endif \
    `endif \
    , .``PNAME``_bresp    (``WNAME``_bresp   ) \
    , .``PNAME``_bvalid   (``WNAME``_bvalid  ) \
    , .``PNAME``_bready   (``WNAME``_bready  )

`define AMBA_AXI_PORT_CONNECTION_AR(PNAME, WNAME) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , .``PNAME``_arid     (``WNAME``_arid    ) \
    `endif \
    `endif \
    , .``PNAME``_araddr   (``WNAME``_araddr  ) \
    `ifdef AMBA_AXI4 \
    , .``PNAME``_arlen    (``WNAME``_arlen   ) \
    , .``PNAME``_arlock   (``WNAME``_arlock  ) \
    , .``PNAME``_arsize   (``WNAME``_arsize  ) \
    , .``PNAME``_arburst  (``WNAME``_arburst ) \
    `ifdef AMBA_AXI_CACHE \
    , .``PNAME``_arcache  (``WNAME``_arcache ) \
    `endif \
    `endif \
    `ifdef AMBA_AXI_PROT \
    , .``PNAME``_arprot   (``WNAME``_arprot  ) \
    `endif \
    , .``PNAME``_arvalid  (``WNAME``_arvalid ) \
    , .``PNAME``_arready  (``WNAME``_arready ) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_QOS \
    , .``PNAME``_arqos    (``WNAME``_arqos   ) \
    `endif \
    `ifdef AMBA_AXI_REGION \
    , .``PNAME``_arregion (``WNAME``_arregion) \
    `endif \
    `endif

`define AMBA_AXI_PORT_CONNECTION_R(PNAME, WNAME) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    , .``PNAME``_rid      (``WNAME``_rid     ) \
    `endif \
    `endif \
    , .``PNAME``_rdata    (``WNAME``_rdata   ) \
    , .``PNAME``_rresp    (``WNAME``_rresp   ) \
    `ifdef AMBA_AXI4 \
    , .``PNAME``_rlast    (``WNAME``_rlast   ) \
    `endif \
    , .``PNAME``_rvalid   (``WNAME``_rvalid  ) \
    , .``PNAME``_rready   (``WNAME``_rready  )

`define AMBA_AXI_PORT_CONNECTION(PNAME, WNAME) \
    `AMBA_AXI_PORT_CONNECTION_AW(``PNAME``, ``WNAME``) \
    `AMBA_AXI_PORT_CONNECTION_W(``PNAME``, ``WNAME``) \
    `AMBA_AXI_PORT_CONNECTION_B(``PNAME``, ``WNAME``) \
    `AMBA_AXI_PORT_CONNECTION_AR(``PNAME``, ``WNAME``) \
    `AMBA_AXI_PORT_CONNECTION_R(``PNAME``, ``WNAME``)

//--------------------------------------------------

`define AMBA_AXI_WIRE_AW(PNAME, ID, AD) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    wire [``ID``-1:0]        ``PNAME``_awid; \
    `endif \
    `endif \
    wire [``AD``-1:0]        ``PNAME``_awaddr; \
    `ifdef AMBA_AXI4 \
    wire [ 7:0]              ``PNAME``_awlen; \
    wire                     ``PNAME``_awlock; \
    wire [ 2:0]              ``PNAME``_awsize; \
    wire [ 1:0]              ``PNAME``_awburst; \
    `ifdef AMBA_AXI_CACHE \
    wire [ 3:0]              ``PNAME``_awcache; \
    `endif \
    `endif \
    `ifdef AMBA_AXI_PROT \
    wire [ 2:0]              ``PNAME``_awprot; \
    `endif \
    wire                     ``PNAME``_awvalid; \
    wire                     ``PNAME``_awready; \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_QOS \
    wire [ 3:0]              ``PNAME``_awqos; \
    `endif \
    `ifdef AMBA_AXI_REGION \
    wire [ 3:0]              ``PNAME``_awregion; \
    `endif \
    `endif

`define AMBA_AXI_WIRE_W(PNAME, ID, DA) \
    wire [``DA``-1:0]        ``PNAME``_wdata; \
    wire [(``DA``/8)-1:0]        ``PNAME``_wstrb; \
    `ifdef AMBA_AXI4 \
    wire                     ``PNAME``_wlast; \
    `endif \
    wire                     ``PNAME``_wvalid; \
    wire                     ``PNAME``_wready;

`define AMBA_AXI_WIRE_B(PNAME, ID) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    wire [``ID``-1:0]        ``PNAME``_bid; \
    `endif \
    `endif \
    wire [ 1:0]              ``PNAME``_bresp; \
    wire                     ``PNAME``_bvalid; \
    wire                     ``PNAME``_bready;

`define AMBA_AXI_WIRE_AR(PNAME, ID, AD) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    wire [``ID``-1:0]        ``PNAME``_arid; \
    `endif \
    `endif \
    wire [``AD``-1:0]        ``PNAME``_araddr; \
    `ifdef AMBA_AXI4 \
    wire [ 7:0]              ``PNAME``_arlen; \
    wire                     ``PNAME``_arlock; \
    wire [ 2:0]              ``PNAME``_arsize; \
    wire [ 1:0]              ``PNAME``_arburst; \
    `ifdef AMBA_AXI_CACHE \
    wire [ 3:0]              ``PNAME``_arcache; \
    `endif \
    `endif \
    `ifdef AMBA_AXI_PROT \
    wire [ 2:0]              ``PNAME``_arprot; \
    `endif \
    wire                     ``PNAME``_arvalid; \
    wire                     ``PNAME``_arready; \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_QOS \
    wire [ 3:0]              ``PNAME``_arqos; \
    `endif \
    `ifdef AMBA_AXI_REGION \
    wire [ 3:0]              ``PNAME``_arregion; \
    `endif \
    `endif

`define AMBA_AXI_WIRE_R(PNAME, ID, DA) \
    `ifdef AMBA_AXI4 \
    `ifdef AMBA_AXI_ID \
    wire [``ID``-1:0]        ``PNAME``_rid; \
    `endif \
    `endif \
    wire [``DA``-1:0]        ``PNAME``_rdata; \
    wire [ 1:0]              ``PNAME``_rresp; \
    `ifdef AMBA_AXI4 \
    wire                     ``PNAME``_rlast; \
    `endif \
    wire                     ``PNAME``_rvalid; \
    wire                     ``PNAME``_rready;

`define AMBA_AXI_WIRE(PNAME, ID, AD, DA) \
    `AMBA_AXI_WIRE_AW(``PNAME``, ``ID``, ``AD``) \
    `AMBA_AXI_WIRE_W(``PNAME``, ``ID``, ``DA``) \
    `AMBA_AXI_WIRE_B(``PNAME``, ``ID``) \
    `AMBA_AXI_WIRE_AR(``PNAME``, ``ID``, ``AD``) \
    `AMBA_AXI_WIRE_R(``PNAME``, ``ID``, ``DA``)

`endif // AXI_VH
