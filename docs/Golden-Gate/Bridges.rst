Target-to-Host Bridges
======================

A custom model in a FireSim Simulation, either CPU-hosted or FPGA-hosted, is
deployed by using a *Target-to-Host Bridge*, or Bridge for short. Bridges provide the
means to inject hardware and software models that produce and consume token streams. 

Bridges enable:

#. **Deterministic, host-agnostic I/O models.** This is the most common use case.
   Here you instantiate bridges at the I/O boundary of your chip, to provide
   a simulation models of the environment your design is executing in.  For an
   FPGA-hosted model, see FASED memory timing models. For co-simulated models
   see the UARTBridge, BlockDeviceBridge, and SerialBridge.

#. **Verification against a software golden model.** Attach an bridge (anywhere
   in your target RTL) to an interface you'd like to monitor, (e.g., a
   processor trace port). In the host, you can pipe the token stream coming off
   this interface to a software model running on a CPU (e.g, a functional ISA
   simulator). See TracerV.

#. **Distributed simulation.** The original FireSim application. You can stitch
   together networks of simulated machines by instantiating bridges at your
   SoC boundary. Then write software models and bridge drivers that move
   tokens between each FPGA. See the SimpleNICBridge.

#. **Resource optimizations.** Resource-intensive components of the target can
   be replaced with models that use fewer FPGA resources or run entirely in
   software.


The use of Bridges in a FireSim simulation has many analogs to doing
mixed-language (Verilog-C++) simulation of the same system in software. Where
possible, we'll draw analogies. After reading this page we encourage you to read the 
:ref:`bridge-walkthrough`, which concretely explains the implementation of the UARTBridge.


Terminology
--------------------------

Bridges have a `target side`, consisting of a specially annotated Module, and `host side`,
which consists of an FPGA-hosted `bridge module` (deriving from ``BridgeModule``)
and an optional CPU-hosted `bridge driver` (deriving from ``bridge_driver_t``).

In a mixed-language software simulation, a verilog procedural interface (VPI) is analogous to the target side of a bridge, with the C++ backing
that interface being the host side.

Target Side
----------------------

In your target side, you will mix-in ``midas.widgets.Bridge`` into a Chisel
``BaseModule`` (this can be a black or white-box Chisel module) and implement
its abstract members. This trait indicates that the associated module will be
replaced with a connection to the host-side of the bridge that sources and
sinks token streams. During compilation, the target-side module will be extracted by Golden Gate and
its interface will be driven by your bridge's host-side implementation.

This trait has two type parameters and two abstract members you'll need define
for your Bridge. Since you must mix ``Bridge`` into a Chisel ``BaseModule``, the IO you
define for that module constitutes the target-side interface of your bridge.

Type Parameters:
++++++++++++++++

#. **Host Interface Type** ``HPType <: TokenizedRecord``: The Chisel type of your Bridge's
   host-land interface. This describes how the target interface has been
   divided into separate token channels. One example, ``HostPortIO[T]``, divides a
   Chisel Bundle into a single bi-directional token stream and is sufficient
   for defining bridges that do not model combinational paths between token
   streams. We suggest starting with ``HostPortIO[T]`` when defining a Bridge for modeling IO devices, as it is the simplest
   to reasonable about and can run at FMR = 1. For other port types, see Bridge Host Interaces.

#. **BridgeModule Type** ``WidgetType <: BridgeModule``: The type of the
   host-land BridgeModule you want Golden Gate to connect in-place of your target-side module.
   Golden Gate will use its class name to invoke its constructor.

Abstract Members:
+++++++++++++++++

#. **Host Interface Mock** ``bridgeIO: HPType``: Here you'll instantiate a mock instance of
   your host-side interface. **This does not add IO to your target-side module**. Instead used
   to emit annotations that tell Golden Gate how the target-land IO of the target-side module is being divided into
   channels.

#. **Bridge Module Constructor Arg** ``constructorArg: Option[AnyRef]``: A optional Scala case class you'd like to pass to your host-land
   BridgeModule's constructor. This will be serialized into an annotation and
   consumed later by Golden Gate. If provided, your case class should capture all
   target-land configuration information you'll need in your Module's
   generator.


Finally at the bottom of your Bridge's class definition **you'll need to call generateAnnotations()**. This is necessary to have Golden Gate properly detect your bridge.

You can freely instantiate your Bridge anywhere in your Target RTL: at the I/O
boundary of your chip or deep in its module hierarchy.  Since all of the Golden
Gate-specific metadata is captured in FIRRTL annotations, you can generate your
target design and simulate it a target-level RTL simulation or even pass it off
to ASIC CAD tools -- Golden Gate's annotations will simply be unused.

What Happens Next?
------------------------

If you pass your design to Golden Gate, it will find your target-side module, remove it,
and wire its dangling target-interface to the top-level of the design. During
host-decoupling transforms, Golden Gate aggregates fields of your bridge's
target interface based on channel annotations emitted by the target-side of
your bridge, and wraps them up into decoupled interfaces that match your host
interface definition. Finally, once Golden Gate is done performing compiler
transformations, it generates the bridge modules (by looking up their
constructors and passing them their serialized constructor argument) and
connects them to the tokenized interfaces presented by the now host-decoupled simulator.

Host Side
---------

The host side of a bridge has two components:

#. A FPGA-hosted bridge module (``BridgeModule``).
#. An optional, CPU-hosted, bridge driver (``bridge_driver_t``).

In general, bridges have both: in FASED memory timing
models, the BridgeModule contains a timing model that exposes timing
parameters as memory-mapped registers that the driver configures  at the start
of simulation.  In the Block Device model, the driver periodically polls queues in the bridge module checking for
new functional requests to be served. In the NIC model, the driver moves
tokens in bulk between the software switch model and the bridge module, which
simply queues up tokens as they arrive.

Communication between a bridge module and driver is implemented with two types of transport:

#. **MMIO**: In the module, this is implemented over a 32-bit AXI4-lite bus.
   Reads and writes to this bus are made by drivers using ``simif_t::read()``
   and ``simif_t::write()``. Bridge modules register memory mapped registers using
   methods defined in ``midas.widgets.Widget``, addresses for these registers are passed to the
   drivers in a generated C++ header.

#. **DMA**: In the module this is implemented with a wide (e.g., 512-bit) AXI4
   bus, that is mastered by the CPU. Bridge drivers initiate bulk transactions
   by passing buffers to ``simif_t::push()`` and ``simif_t::pull()`` (DMA from the
   FPGA). DMA is typically used to stream tokens into and out of
   out of large FIFOs in the BridgeModule.


Compile-Time (Parameterization) vs Runtime Configuration
--------------------------------------------------------

As when compiling a software RTL simulator, the simulated design
is configured over two phases:

#. **Compile Time**, by parameterizing the target RTL and BridgeModule
   generators, and by enabling Golden Gate optimization and debug
   transformations. This changes the simulator's RTL and thus requires a
   FPGA-recompilation. This is equivalent to, but considerably slower than,
   invoking VCS to compile a new simulator.

#. **Runtime**, by specifying plus args (e.g., +latency=1) that are passed to
   the BridgeDrivers.  This is equivalent to passing plus args to a software
   RTL simulator, and in many cases the plus args passed to an RTL simulator
   and a FireSim simulator can be the same.

Target-Side vs Host-Side Parameterization
-----------------------------------------

Unlike in a software RTL simulation, FireSim simulations have an additional phase of RTL
elaboration, during which bridge modules are generated (they are themselves Chisel generators).

The parameterization of your bridge module can be captured in two places.

#. **Target side.** here parameterization information is provided both as free
   parameters to the target's generator, and extracted from the context in
   which the bridge is instantiated. The latter might include things like widths
   of specific interfaces or bounds on the behavior the target might expose to
   the bridge (e.g., a maximum number of inflight requests). All of this
   information must be captured in a _single_ serializable constructor argument,
   generally a case class (see ``Bridge.constructorArg``).

#. **Host side.** This is parameterization information captured in Golden Gate's
   ``Parameters`` object.  This should be used to provide host-land implementation
   hints (that ideally don't change the simulated behavior of the system), or to
   provide arguments that cannot be serialized to the annotation file.

In general, if you can capture target-behavior-changing parameterization information from
the target-side you should. This makes it easier to prevent divergence between
a software RTL simulation and FireSim simulation of the same FIRRTL. It's also easier to
configure multiple instances of the same type of bridge from the target side.
