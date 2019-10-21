Target-to-Host Bridges
======================

A custom model in a FireSim Simulation, either CPU-hosted or FPGA-hosted, is
deployed by a *Target-to-Host Bridge*, or Bridge for short. Bridges provide the
means to inject hardware and software models that produce and consume token streams. 

Bridges enable:

#. Software co-simulation. Ex. Before writing RTL for your accelerator, you can instantiate a custom bridge that
   calls out to a software model running on the CPU.

#. Resource savings by replacing components of the target with models that use
   fewer FPGA resources or run entirely software.

The use of Bridges in a FireSim simulation has many analogs to doing
mixed-language (Verilog-C++) simulation of the same system in software. Where
possible, we'll draw analogies.


Use Cases
---------

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


Defining A Bridge
--------------------------

Bridges have a target side, consisting of a specially annotated Module, and host side,
which consist of an FPGA-hosted BridgeModule and an optional CPU-hosted BridgeDriver.

In a mixed-language software simulation, a Verilog VPI interface, (i.e, a tick
fucntion) is analogous to the target side of a bridge, with the C++ backing
that interface being the host side.


Target Side
----------------------

In your target-side implementation, you will define a Scala trait that extends
Bridge. This trait indicates that the module will declared and connected to in
the target design, but that its implementation will be provided by a simulation
Bridge. Once the trait is mixed into a Chisel BlackBox or a Module, that module
will be extracted by Golden Gate, and its interface with the rest of the target
design will be driven by your host-side implementation.

This trait has two type parameters and two abstract members you'll need define
for your Bridge. Note that since you must mix Bridge into either a Chisel
BlackBox or a Module, you'll of course need to define the IO for that module.
That's the interface you'll use to connect to your target RTL.

Type Parameters:

#. Host Interface Type [HPType]: The Chisel type of your Bridge's target-land interface. This describes how the target interface
has been divided into seperate token channels. One example, HostPort[T], divides a Chisel Bundle into a single bi-directional token stream.
#. Host Module Type: The type of the Chisel Module you want Golden Gate to connect in-place of your black box.

Abstract Members:

#. Host Interface Mock: In your bridge trait you'll create an instance of
   your Host Interface of type HPType, which you'll use to communicate to
   Golden Gate how the target-land IO of this black box is being divided into
   channels.  The constructor of thisr must accept the target-land IO
   interface, a hardware type, that it may correctly divide it into channels,
   and annotate the right fields of your Bridge instance.

#. Constructor Arg: A Scala case class you'd like to pass to your host-land
   BridgeModule's constructor. This will be serialized into an annotation and
   consumed later by Golden Gate. In this case class you should capture all
   target-land configuration information you'll need in your Module's
   generator.


Finally at the bottom of your Bridge's class definition **you'll need to call generateAnnotations()**.
This will emit an "BridgeAnnotation" attached to module that indicates:

#. This module is an Bridge.
#. The class name of the BridgeModule's generator (e.g., firesim.bridges.UARTModule)
#. The serialized constructor argument for that generator (e.g. firesim.bridges.UARTKey)
#. A list of channel names; string references to Channel annotations

And a series of FAMEChannelConnectionAnnotations, which target the module's I/O to group them into token channels.

You can freely instantiate your Bridge anywhere in your Target RTL: at the I/O
boundary of your chi or deep in its module hierarchy.  Since all of the Golden
Gate-specific metadata is captured in FIRRTL annotations, you can generate your
target design and simulate it a target-level RTL simulation or even pass it off
to ASIC CAD tools -- Golden Gate's annotations will simply be unused.

What Happens Next?
------------------------

If you do pass your FIRRTL & Annotations to Golden Gate. It will find your
module, remove it,  and wire its dangling target-interface to the top-level of
the design. During host-decoupling transforms, Golden Gate aggregates fields of
your bridge's target IO based on ChannelAnnotations, and wraps them up into
new Decoupled interfaces that match your Host Interface definition. Finally,
once Golden Gate is done performing compiler transformations, it iterates
through each Bridge annotation, generates your Module, passing it the
serialized constructor argument, and connects it to the tokenized interface
presented by the now host-decoupled target.

Host-side Implementation
------------------------

Host-side implementations have two components.
#. A FPGA-hosted BridgeModule.
#. An optional, CPU-hosted, bridge driver.

In general, bridges have both a module and a driver: in FASED memory timing
models, the BridgeDriver configures timing parameters at the start of
simulation, and periodically reads instrumentation during execution.  In the
Block Device model, a Driver periodically polls hardware queues checking for
new functional requests to be served. In the NIC model, the BridgeDriver moves
tokens in bulk between the software switch model and the BridgeModule, which
simply queues up tokens as they arrive.

Communication between a BridgeModule and BridgeDriver is implemented with two types of transport:

#. MMIO: On the hardware-side this is implemented over a 32-bit AXI4-lite bus.
   Reads and writes to this bus are made by BridgeDrivers using simif_t::read()
   and simif_t::write(). BridgeModules register memory mapped registers using
   methods defined in Widget, addresses for these registers are passed to the
   drivers in a generated C++ header.

#. DMA: On the hardware-side this is implemented with a wide (e.g., 512-bit) AXI4
   bus, that is mastered by the CPU. BridgeDrivers initiate bulk transactions
   by passing buffers to simif_t::push() and simif_t::pull() (DMA from the
   FPGA). DMA is typically used to stream tokens into and out of
   out of large FIFOs in the BridgeModule.


Compile-Time (Parameterization) vs Runtime Configuration
--------------------------------------------------------

As when compiling a software-RTL simulator, the simulated design
is configured over two phases:

#. Compile Time. By parameterization the target RTL and BridgeModule
   generators, and by enabling Golden Gate optimization and debug
   transformations. This changes the simulator's RTL and thus requires a
   FPGA-recompilation. This is equivalent to, but considerably slower than,
   invoking VCS to compile a new simulator.


#. Runtime. By specifying plus args (e.g., +mm_latency=1) that are passed to
   the BridgeDrivers.  This is isomorphic to passing plus args to a VCS
   simulator, in fact, in many cases the plus args passed to a VCS simulator
   and a FireSim simulator can be the same.

Target-Side vs Host-Side Parameterization
-----------------------------------------

Unlike in a VCS simulation, FireSim simulations have an additional phase of RTL
elaboration, during which BridgeModules are generated (they are implemented as
Chisel generators).

The parameterization of your bridge module can be captured in two places.

#. Target-side: Here parameterization information is provided both as free
   parameters to the target's generator, and extracted from the context in
   which the Bridge is instantiated. The latter might include things like width
   of specific interfaces or bounds on the behavior the target might expose to
   the Bridge (e.g., a maximum number of inflight requests). All of this
   information must be captured in a single serializable constructor argument,
   generally a case class (see Endpoint.constructorArg).

#. Host-side: This is parameterization information captured in Golden Gate's
   Parameters object.  This should be used to provide host-land implementation
   hints (that don't change the simulated behavior of the system), or to
   provide arguments that cannot be serialized to the annotation file.


In general, if you can capture target-behavior-changing parameterization information from
the target-side you should. This makes it easier to prevent divergence between
a RTL simulation and FireSim simulation of the same FIRRTL. It's also easier to
configure multiple instances of the same type of bridge from the target-side.
