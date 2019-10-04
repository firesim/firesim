Target-to-Host Bridges
======================

Deploying your own Models using Target-to-Host Bridges, or Bridges for short, is the primary way you will customize your FireSim
simulation. As their name suggests,  Just as their name suggests, Bridges connect the target and host worlds, by allowing you to write
custom RTL and software that act on token streams directly.

Bridges enable:

#. Software co-simulation. Ex. Before writing RTL for your accelerator, you can instantiate a custom endpoint that
   calls out to a software model running on the CPU.

#. Resource savings by replacing components of the target with models that use
   fewer FPGA resources or run entirely software.

Use Cases
---------

#. **Deterministic, host-agnostic I/O models.** This is the most common use case.
   Here you instantiate endpoints at the I/O boundary of your chip, to provide
   a simulation models of the environment your design is executing in.  For an
   FPGA-hosted model, see FASED memory timing models. For co-simulated models
   see the UARTEndpoint, BlockDeviceEndpoint, and SerialEndpoint.

#. **Verification against a software golden model.** Attach an endpoint (anywhere
   in your target RTL) to an interface you'd like to monitor, (e.g., a
   processor trace port). In the host, you can pipe the token stream coming off
   this interface to a software model running on a CPU (e.g, a functional ISA
   simulator). See TracerV.

#. **Distributed simulation.** The original FireSim application. You can stitch
   together networks of simulated machines by instantiating endpoints at your
   SoC boundary. Then write software models and endpoint drivers that move
   tokens between each FPGA. See the SimpleNICEndpoint.


Defining A Bridge
--------------------------

Bridges have a target-land definition and a host-land implementation.

Target-Land Definition
----------------------

In your target-land definition, you will define, Scala trait that extends
Bridge that mixes into a Chisel Black Box, or a Module you'd like to replace.

This trait has two type parameters and two abstract members you'll need define
for your Bridge. Note that since you must mix Bridge into either a Chisel
Black Box or a Module, you'll of course need to define the IO for that module.
That's the interface you'll use to connect to your target RTL.

Type Paramaters:

#. Host Interface Type [HPType]: The Chisel type of your Bridge's target-land interface. This describes how the target interface
has been divided into seperate token channels. One example, HostPort[T], divides a chisel Bundle into a single bi-directional token stream.
#. Host Module Type: The type of the Chisel Module you want Golden Gate to connect in-place of your black box.

Abstract Members:

#. Host Interface Mock: In your endpoint trait you'll create an instance of
   your Host Interface of type HPType, which you'll use to communicate to
   Golden Gate how the target-land IO of this black box is being divided into
   channels.  The constructor of thisr must accept the target-land IO
   interface, a hardware type, that it may correctly divide it into channels,
   and annotate the right fields of your Bridge instance.

#. Constructor Arg: A Scala case class you'd like to pass to your host-land
   widget's constructor. This will be serialized into an annotation and
   consumed later by Golden Gate. In this case class you should capture all
   target-land configuration information you'll need in your Module's
   generator.


Finally at the bottom of your Bridge's class definition **you'll need to call generateAnnotations()**.
This will emit an "BridgeAnnotation" that indicate:

#. This module is an Bridge.
#. The class name of the widget's generator (e.g., firesim.endpoints.UARTModule)
#. The serialized constructor argument for that generator (e.g. firesim.endpoints.UARTKey)
#. A list of channel names; string references to Channel annotations

And a series of FAMEChannelConnectionAnnotations, which target the module's I/O to group them into token channels.

You can freely instantiate your Bridge anywhere in your Target RTL: deep in
your module hierarchy or at the I/O boundary.  Since all of the Golden
Gate-specific metadata is captured in FIRRTL annotations, you can generate your
target design and simulate it a target-level RTL simulation or even pass it off
to ASIC CAD tools -- Golden Gate's annotations will simply be unused.

What Happens Next?
------------------------

If you do pass your FIRRTL & Annotations to Golden Gate. It will find your
module, remove it,  and wire it's dangling target-interface to the top-level of
the design. During host-decoupling transforms, Golden Gate aggregates fields of
your endpoint's target IO based on ChannelAnnotations, and wraps them up into
new Decoupled interfaces that match your Host Interface definition. Finally,
once Golden Gate is done performing compiler transformations, it iterates
through each Bridge annotation, generates your Module, passing it the
serialized constructor argument, and connects it to the tokenized interface
presented by the now host-decoupled target.

Host-Land Implementation
------------------------

Bridge implementations have two components.
#. A FPGA-hosted BridgeModule.
#. A CPU-hosted BridgeDriver.

both a driver and a widget. In FASED memory timing models, 
the Bridge Driver configures timing parameters at the start of simulation, and periodically reads instrumentation during execution.
In the Block Device model, a Driver periodically polls hardware queues checking for new functional requests to be served. In the NICBridge,
the endpoint Driver moves tokens in bulk between the BridgeModule and a software switch model.

Golden Gate provides two transport types to implement these features.

#. MMIO: On the hardware-side this is implemented over a 32-bit AXI4-lite bus. Reads and writes to this bus are made by Bridge Drivers
   using simif_t::read() and simif_t::write(); BridgeModules register memory mapped registers using methods defined in Widget, addresses for 
   these registers are passed to the drivers in a generated C++ header.

#. DMA: On the hardware-side this is implemented with a wide (512-bit) AXI4 bus, that is mastered by the CPU. Bridge Drivers initiate
   bulk transactions by passing buffers to simif_t::push() and simif_t::pull()
   (DMA from the FPGA). Typically is used to stream tokens directly into and
   out of large FIFOs provided in the widget.


Host vs Target-land Configuration
---------------------------------

An Example MMIO-Based Bridge
------------------------------

An Example DMA-Based Bridge
-----------------------------
