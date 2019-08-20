Target Abstraction & Host Decoupling
====================================

MIDAS-generated simulators are deterministic, cycle-exact representations of
the source RTL fed to the compiler. To achieve this, MIDAS consumes input RTL
(as FIRRTL) and transforms it into a latency-insensitive bounded dataflow
network (LI-BDN) representation of the same RTL.

The Target as a Dataflow Graph
------------------------------

Dataflow graphs in MIDAS consist of models, tokens, and channels:

1) Models -- the nodes of the graph, these capture the behavior of the target machine by consuming and producing tokens.

2) Tokens -- the messages of dataflow graph, these represent a hardware value as they would appear on a wire after they have converged for a given cycle.

3) Channels -- the edges of the graph, these connect the output port of one model to the input of another.

In this system, time advances locally in each model. A model advances once
cycle in simulated time when it consumes one token from each of its input ports
and enqueues one token into each of its output ports. Models are
*latency-insensitive*: they can tolerate variable input token latency as well
as backpressure on output channels. Give a sequence of input tokens for each
input port, a correctly implemented model will produce the same sequence of
tokens on each of its outputs, regardless of when those input tokens arrive.

We give an example below of a dataflow graph representation of a 32-bit adder, simulating two cycles of execution.

Model Implementations
---------------------

In MIDAS, there are two dimensions of model implementation:

1) CPU-hosted or FPGA-hosted: simply, where the model is going to be hosted.
CPU-hosted models are software and thus are more flexible and easy
to debug but slow. Conversely, FPGA-hosted models are fast, but are harder to debug,
and difficult to write~(if they aren't transformed from RTL by the compiler).

2) Cycle-Exact or Abstract: cycle-exact models faithfully implement a chunk of
the SoC's RTL~(this formalized later), where as abstract models are
handwritten and trade fidelity for reduced complexity, better simulation performance,
improved resource utilization, etc...

Hybrid, CPU-FPGA-hosted models are common. Here, a common pattern is write an RTL
timing-model and a software functional model.

For example, FireSim's block
device model hosts its fixed-latency timing model on the FPGA, but its
functional model runs on the CPU which allows it to use the host-CPU's
filesystem as a backing store. The latency pipe dequeues an input token as they
arrive, buffering requests as they appear and keeping track of cycle at which
it must return response for that request. It enqueues output tokens,
until it reaches a cycle at which it is supposed to return a response. Here it
checks a functional response queue, and if no response is available it fails to
enqueue an output token, stalling the downstream model.
In parallel, the CPU-hosted functional model polls the FPGA-hosted component for requests to serve, and serves them, and enqueues responses
into the functional reponse queue.

Expressing the Target Graph
---------------------------

The target graph is captured implicitly in the FIRRTL for your target. The bulk
of the RTL for your system will be transformed by Golden Gate into one or more
cycle-exact, FPGA-hosted models. You introduce abstract, FPGA-hosted models and
CPU-hosted models into the graph by instantiating specially annotated FIRRTL
black-boxes, called Endpoints. During compilation, Golden Gate promotes these
black boxes to create new token channels, and then
instantiates your custom EndpointWidget Module to source and sink these token
channels. It is in this widget you model target behavior by writing RTL &
software that sources and sinks tokens. We describe the procedure for developing
a custom endpoint in the Endpoints section.


Latency-Insensitive Bounded Dataflow Networks
---------------------------------------------

In order for the resulting simulator to be a faithful representation of the target RTL. 
Models must adhere to three properties. We refer the reader to TODO for the formal definitions of these properties.
English language equivalents follow.

Partial Implementation: The model output token behavior matches the cycle-by-cyle output of the reference RTL,
given the same input provided to both the reference RTL and the model (as a arbitrarily delayed token stream).

Self Cleaning: A model that has enqueued N tokens into each of it's output ports _must_ eventually dequeue N tokens
from each of it's input ports.

No Extraenous Dependencies: Once the input tokens required to compute an output
token become available, the model must eventually enqueue that output token.



A Foolproof Algorithm For LI-BDN Implementation from Reference RTL
---------------------------------

Write the RTL for your model assuming no host-decoupling and clock gate it.


Common Bugs Implementing LI-BDN Properties
------------------------------------------

Implementing these properties incorrectly will produce a buggy simulator.

Failing to implement:

PI: unexpected simulation results, non-deterministic execution
-> Check output token sequence matches the behavior of reference RTL (if available)
-> Fuzz token input token timing, ensure output token sequence is unchanged

NED: simulation deadlock
-> ensure output enqueue guard depends only tokens to which it is combinationally dependent
-> e.g., if an output token depends only on target state, it should have no dependency on any input port (it should be able to enqueue a new output immediate after the model state has advanced one cycle)

SC: simulation deadlock 







