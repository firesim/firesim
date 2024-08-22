.. _bridge-deep-dive:

Bridge Deep Dive
================

..
    warning: This documentation was written on 08/19/2023. Current APIs are experimental and are subject to change.

In this section, we'll walkthrough a simple Target-to-Host bridge associated with
Chipyard called the UARTBridge. It serves as an example of how to use FireSim as a
library to create your own bridges. The UARTBridge uses host-MMIO to model a UART
device.

Reading the :ref:`target-to-host-bridges` section is a prerequisite to reading these
sections.

UART Bridge (Host-MMIO)
-----------------------

Source code for the UART Bridge lives in Chipyard in the
:cy-gh-file-ref:`generators/firechip` area. Specifically, the following directories:

.. code-block:: text

    chipyard/generators/firechip/
        bridgeinterfaces/
            src/main/scala/
                UART.scala # Chisel IOs and Scala case classes shared between FireSim/Chipyard
        goldengateimplementations/
            src/main/scala/
                UARTBridge.scala # BridgeModule definition
        bridgestubs/
            src/main/
                scala/uart/UARTBridge.scala # Target-Side Bridge
                cc/bridges/uart.cc          # Bridge Driver source
                cc/bridges/uart.h           # Bridge Driver header
        chip/
            src/main/
                cc/firesim/firesim_top.cc # Driver instantiation in the main simulation driver
                makefrag/firesim/ # Target-specific build rules
                    build.mk      # Definition of the Chisel elaboration step
                    config.mk     # Target-specific configuration and path setup
                    driver.mk     # Build rules for the driver
                    metasim.mk    # Custom run commands for meta-simulation

Target Side
~~~~~~~~~~~

The first order of business when designing a new bridge is to implement its target side.
In the case of UART we've defined a Chisel BlackBox [1]_ extending Bridge. We'll
instantiate this BlackBox and connect it to UART IO in the top-level of our chip. We
first define a class that captures the target-side interface of the Bridge (in
:cy-gh-file-ref:`generators/firechip/bridgeinterfaces/src/main/scala/UART.scala`):

.. code-block:: Scala

    class UARTPortIO extends Bundle {
       val txd = Output(Bool())
       val rxd = Input(Bool())
     }

     class UARTBridgeTargetIO extends Bundle {
       val clock = Input(Clock())
       val uart = Flipped(new UARTPortIO)
       // Note this reset is optional and used only to reset target-state modeled
       // in the bridge. This reset is just like any other Bool included in your target
       // interface, simply appears as another Bool in the input token.
       val reset = Input(Bool())
     }

.. [1] You can also extend a non-BlackBox Chisel Module, but any Chisel source contained
    within will be removed by Golden Gate. You may wish to do this to enclose a
    synthesizable model of the Bridge for other simulation backends, or simply to wrap a
    larger chunk RTL you wish to model in the host-side of the Bridge.

Here, we define a case class (in
:cy-gh-file-ref:`generators/firechip/bridgeinterfaces/src/main/scala/UART.scala`) that
carries additional metadata to the host-side BridgeModule. For UART, this is simply the
clock-division required to produce the baudrate:

.. code-block:: scala

    // Out bridge module constructor argument. This captures all of the extra
    // metadata we'd like to pass to the host-side BridgeModule. Note, we need to
    // use a single case class to do so, even if it is simply to wrap a primitive
    // type, as is the case for the div Int.
    case class UARTKey(div: Int)

Both these IOs and the case class needs be fully isolated from the target. This means
that they should compile with FireSim's Chisel version (Chisel 3) and not include any
target-specific classes, IOs, etc. Both the IOs and the case class is compiled in the
target and is copied to FireSim to be compiled there as well.

Finally, we define the actual target-side module (specifically, a BlackBox)(in
:cy-gh-file-ref:`generators/firechip/bridgestubs/src/main/scala/uart/UARTBridge.scala`):

.. code-block:: scala

    class UARTBridge(initBaudRate: BigInt, freqMHz: Int)(implicit p: Parameters) extends BlackBox
        with Bridge[HostPortIO[UARTBridgeTargetIO]] {
      // Module portion corresponding to this bridge
      val moduleName = "firechip.firesimonly.bridges.UARTBridgeModule"
      // Since we're extending BlackBox this is the port will connect to in our target's RTL
      val io = IO(new UARTBridgeTargetIO)
      // Implement the bridgeIO member of Bridge using HostPort. This indicates that
      // we want to divide io, into a bidirectional token stream with the input
      // token corresponding to all of the inputs of this BlackBox, and the output token consisting of
      // all of the outputs from the BlackBox
      val bridgeIO = HostPort(io)

      // Do some intermediate work to compute our host-side BridgeModule's constructor argument
      val div = (BigInt(freqMHz) * 1000000 / initBaudRate).toInt

      // And then implement the constructorArg member
      val constructorArg = Some(UARTKey(div))

      // Finally, and this is critical, emit the Bridge Annotations -- without
      // this, this BlackBox would appear like any other BlackBox to Golden Gate
      generateAnnotations()
    }

To make it easier to instantiate our target-side module, we've also defined an optional
companion object (in
:cy-gh-file-ref:`generators/firechip/bridgestubs/src/main/scala/uart/UARTBridge.scala`):

.. code-block:: scala

    object UARTBridge {
      def apply(clock: Clock, uart: sifive.blocks.devices.uart.UARTPortIO, reset: Bool, freqMHz: Int)(implicit p: Parameters): UARTBridge = {
        val ep = Module(new UARTBridge(uart.c.initBaudRate, freqMHz))
        ep.io.uart.txd := uart.txd
        uart.rxd := ep.io.uart.rxd
        ep.io.clock := clock
        ep.io.reset := reset
        ep
      }
    }

This target-side module doesn't compile with FireSim at all (except for the APIs given
by the ``firesim-lib`` Scala project).

That completes the target-side definition.

Host-Side BridgeModule
~~~~~~~~~~~~~~~~~~~~~~

The remainder of the file is dedicated to the host-side BridgeModule definition. Here we
have to process tokens generated by the target, and expose a memory-mapped interface to
the bridge driver.

Inspecting the top of the class (in
:cy-gh-file-ref:`generators/firechip/goldengateimplementations/src/main/scala/UARTBridge.scala`):

.. code-block:: scala

    // Our UARTBridgeModule definition, note:
    // 1) it takes one parameter, key, of type UARTKey --> the same case class we captured from the target-side
    // 2) It accepts one implicit parameter of type Parameters
    // 3) It extends BridgeModule passing the type of the HostInterface
    //
    // While the Scala type system will check if you parameterized BridgeModule
    // correctly, the types of the constructor arugument (in this case UARTKey),
    // don't match, you'll only find out later when Golden Gate attempts to generate your module.
    class UARTBridgeModule(key: UARTKey)(implicit p: Parameters) extends BridgeModule[HostPortIO[UARTBridgeTargetIO]]()(p) {
      lazy val module = new BridgeModuleImp(this) {
        val div = key.div
        // This creates the interfaces for all of the host-side transport
        // AXI4-lite for the simulation control bus, =
        // AXI4 for DMA
        val io = IO(new WidgetIO())

        // This creates the host-side interface of your TargetIO
        val hPort = IO(HostPort(new UARTBridgeTargetIO))

        // Generate some FIFOs to capture tokens...
        val txfifo = Module(new Queue(UInt(8.W), 128))
        val rxfifo = Module(new Queue(UInt(8.W), 128))

        val target = hPort.hBits.uart
        // In general, your BridgeModule will not need to do work every host-cycle. In simple Bridges,
        // we can do everything in a single host-cycle -- fire captures all of the
        // conditions under which we can consume and input token and produce a new
        // output token
        val fire = hPort.toHost.hValid && // We have a valid input token: toHost ~= leaving the transformed RTL
                   hPort.fromHost.hReady && // We have space to enqueue a new output token
                   txfifo.io.enq.ready      // We have space to capture new TX data
        val targetReset = fire & hPort.hBits.reset
        rxfifo.reset := reset.asBool || targetReset
        txfifo.reset := reset.asBool || targetReset

        hPort.toHost.hReady := fire
        hPort.fromHost.hValid := fire

Most of what follows is responsible for modeling the timing of the UART. As a bridge
designer, you're free to take as many host-cycles as you need to process tokens. In
simpler models, like this one, it's often easiest to write logic that operates in a
single cycle but gate state-updates using a "fire" signal that is asserted when the
required tokens are available.

Now, we'll skip to the end to see how to add registers to the simulator's memory map
that can be accessed using MMIO from bridge driver.

.. code-block:: scala

    // Exposed the head of the queue and the valid bit as a read-only registers
    // with name "out_bits" and out_valid respectively
    genROReg(txfifo.io.deq.bits, "out_bits")
    genROReg(txfifo.io.deq.valid, "out_valid")

    // Generate a writeable register, "out_ready", that when written to dequeues
    // a single element in the tx_fifo. Pulsify derives the register back to false
    // after pulseLength cycles to prevent multiple dequeues
    Pulsify(genWORegInit(txfifo.io.deq.ready, "out_ready", false.B), pulseLength = 1)

    // Generate regisers for the rx-side of the UART; this is eseentially the reverse of the above
    genWOReg(rxfifo.io.enq.bits, "in_bits")
    Pulsify(genWORegInit(rxfifo.io.enq.valid, "in_valid", false.B), pulseLength = 1)
    genROReg(rxfifo.io.enq.ready, "in_ready")

    // This method invocation is required to wire up all of the MMIO registers to
    // the simulation control bus (AXI4-lite)
    genCRFile()

This module is injected into the FireSim compiler and is never compiled by the target.
Thus it has access to all APIs given by MIDAS.

Host-Side Driver
~~~~~~~~~~~~~~~~

To complete our host-side definition, we need to define a CPU-hosted bridge driver.
Bridge Drivers extend the ``bridge_driver_t`` interface, which declares 5 virtual
methods a concrete bridge driver must implement:

.. literalinclude:: ../../sim/midas/src/main/cc/core/bridge_driver.h
    :language: c++
    :start-after: DOC include start: Bridge Driver Interface
    :end-before: DOC include end: Bridge Driver Interface

The declaration of the UART bridge is inlined below from
:cy-gh-file-ref:`generators/firechip/bridgestubs/src/main/cc/bridges/uart.h`:

.. code-block:: c++

    #ifndef __UART_H
    #define __UART_H

    #include "bridges/serial_data.h"
    #include "core/bridge_driver.h"

    #include <cstdint>
    #include <memory>
    #include <optional>
    #include <signal.h>
    #include <string>
    #include <vector>

    /**
     * Structure carrying the addresses of all fixed MMIO ports.
     *
     * This structure is instantiated when all bridges are populated based on
     * the target configuration.
     */
    struct UARTBRIDGEMODULE_struct {
      uint64_t out_bits;
      uint64_t out_valid;
      uint64_t out_ready;
      uint64_t in_bits;
      uint64_t in_valid;
      uint64_t in_ready;
    };

    /**
     * Base class for callbacks handling data coming in and out a UART stream.
     */
    class uart_handler {
    public:
      virtual ~uart_handler() = default;

      virtual std::optional<char> get() = 0;
      virtual void put(char data) = 0;
    };

    class uart_t final : public bridge_driver_t {
    public:
      /// The identifier for the bridge type used for casts.
      static char KIND;

      /// Creates a bridge which interacts with standard streams or PTY.
      uart_t(simif_t &simif,
             const UARTBRIDGEMODULE_struct &mmio_addrs,
             int uartno,
             const std::vector<std::string> &args);

      ~uart_t() override;

      void tick() override;

    private:
      const UARTBRIDGEMODULE_struct mmio_addrs;
      std::unique_ptr<uart_handler> handler;

      serial_data_t<char> data;

      void send();
      void recv();
    };

    #endif // __UART_H

The bulk of the driver's work is done in its ``tick()`` method. Here, the driver polls
the BridgeModule and then does some work. Note: the name, ``tick`` is vestigial: one
invocation of tick() may do work corresponding to an arbitrary number of target cycles.
It's critical that tick be non-blocking, as waiting for work from the BridgeModule may
deadlock the simulator.

Build-System Modifications
~~~~~~~~~~~~~~~~~~~~~~~~~~

The final consideration in adding your bridge concerns the build system. You should be
able to host the Scala sources for your bridge with rest of your target RTL: SBT will
make sure those classes are available on the runtime classpath. If you're hosting your
bridge driver sources outside of the existing directories, you'll need to modify your
target-project make fragments to include them. The default Chipyard/Rocket Chip-based
one lives in :cy-gh-file-ref:`generators/firechip/chip/src/main/makefrag/firesim`.

Here the main order of business is to add header and source files to ``DRIVER_H`` and
``DRIVER_CC`` respectively in `driver.mk`, by modifying the lines below:

.. code-block:: make

    ##########################
    # Driver Sources & Flags #
    ##########################

    ifeq (,$(wildcard $(RISCV)/lib/libriscv.so))
    $(warning libriscv not found)
    LRISCV=
    else
    LRISCV=-lriscv
    endif

    firechip_lib_dir = $(chipyard_dir)/generators/firechip/chip/src/main/cc
    firechip_bridgestubs_lib_dir = $(chipyard_dir)/generators/firechip/bridgestubs/src/main/cc
    testchipip_csrc_dir = $(chipyard_dir)/generators/testchipip/src/main/resources/testchipip/csrc

    # DRIVER_H only used to update recipe pre-reqs (ok to track more files)

    # fesvr and related srcs
    DRIVER_H += \
                    $(shell find $(testchipip_csrc_dir) -name "*.h") \
                    $(shell find $(firechip_bridgestubs_lib_dir)/fesvr -name "*.h")
    DRIVER_CC += \
                    $(testchipip_csrc_dir)/cospike_impl.cc \
                    $(testchipip_csrc_dir)/testchip_tsi.cc \
                    $(testchipip_csrc_dir)/testchip_dtm.cc \
                    $(testchipip_csrc_dir)/testchip_htif.cc \
                    $(firechip_bridgestubs_lib_dir)/fesvr/firesim_tsi.cc \
                    $(firechip_bridgestubs_lib_dir)/fesvr/firesim_dtm.cc \
                    $(RISCV)/lib/libfesvr.a
    # Disable missing override warning for testchipip.
    TARGET_CXX_FLAGS += \
                    -isystem $(testchipip_csrc_dir) \
                    -isystem $(RISCV)/include \
                    -Wno-inconsistent-missing-override
    TARGET_LD_FLAGS += \
                    -L$(RISCV)/lib \
                    -Wl,-rpath,$(RISCV)/lib \
                    $(LRISCV)

    # top-level sources
    DRIVER_CC += $(addprefix $(firechip_lib_dir)/firesim/, $(addsuffix .cc, firesim_top))
    TARGET_CXX_FLAGS += -I$(firechip_bridgestubs_lib_dir)/bridge/test

    # bridge sources
    DRIVER_H += $(shell find $(firechip_bridgestubs_lib_dir) -name "*.h")
    DRIVER_CC += \
                    $(wildcard \
                            $(addprefix \
                                    $(firechip_bridgestubs_lib_dir)/, \
                                    $(addsuffix .cc,bridges/* bridges/tracerv/* bridges/cospike/*) \
                            ) \
                    )
    TARGET_CXX_FLAGS += \
                    -I$(firechip_bridgestubs_lib_dir) \
                    -I$(firechip_bridgestubs_lib_dir)/bridge \
                    -I$(firechip_bridgestubs_lib_dir)/bridge/tracerv \
                    -I$(firechip_bridgestubs_lib_dir)/bridge/cospike
    TARGET_LD_FLAGS += \
            -l:libdwarf.so -l:libelf.so \
            -lz \

    # other
    TARGET_CXX_FLAGS += \
                    -I$(GENERATED_DIR) \
                    -g

Then the other ``.mk`` files in the directory handle copying sources from the target to
FireSim, building RTL, and more. That's it! At this point you should be able to both
test your bridge in software simulation using metasimulation, or deploy it to an FPGA.
