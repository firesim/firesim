Target Abstraction & Host Decoupling
====================================

Golden Gate-generated simulators are deterministic, cycle-exact representations of
the source RTL fed to the compiler. To achieve this, Golden Gate consumes input RTL
(as FIRRTL) and transforms it into a latency-insensitive bounded dataflow
network (LI-BDN) representation of the same RTL.

The Target as a Dataflow Graph
------------------------------

Dataflow graphs in Golden Gate consist of models, tokens, and channels:

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

In Golden Gate, there are two dimensions of model implementation:

1) CPU- or FPGA-hosted: simply, where the model is going to execute.
CPU-hosted models, being software, are more flexible and easy
to debug but slow. Conversely, FPGA-hosted models are fast, but more difficult to write and debug.

2) Cycle-Exact or Abstract: cycle-exact models faithfully implement a chunk of
the SoC's RTL~(this formalized later), where as abstract models are
handwritten and trade fidelity for reduced complexity, better simulation performance,
improved resource utilization, etc...

Hybrid, CPU-FPGA-hosted models are common. Here, a common pattern is write an RTL
timing-model and a software functional model.

Expressing the Target Graph
---------------------------

The target graph is captured in the FIRRTL for your target. The bulk of the RTL
for your system will be transformed by Golden Gate into one or more
cycle-exact, FPGA-hosted models. You introduce abstract, FPGA-hosted models and
CPU-hosted models into the graph by using Target-to-Host Bridges. During
compilation, Golden Gate extracts the target-side of the bridge, and
instantiates your custom RTL, called an BridgeModule, which together with a
CPU-hosted Bridge Driver, gives you the means to model arbitrary
target-behavior. We expand on this in the Bridge section.


Latency-Insensitive Bounded Dataflow Networks
---------------------------------------------

In order for the resulting simulator to be a faithful representation of the
target RTL, models must adhere to three properties. We refer the reader to
`the LI-BDN paper <https://dspace.mit.edu/bitstream/handle/1721.1/58834/Vijayaraghavan-2009-Bounded%20Dataflow%20Networks%20and%20Latency-Insensitive%20Circuits.pdf?sequence=1&isAllowed=y>`_
for the formal definitions of these properties.  English language equivalents
follow.

**Partial Implementation**: The model output token behavior matches the
cycle-by-cyle output of the reference RTL, given the same input provided to
both the reference RTL and the model (as a arbitrarily delayed token stream).
Cycle exact models must implement PI, whereas abstract models do not.

The remaining two properties ensure the graph does not deadlock, and must be
implemented by both cycle-exact and abstract models.

**Self-Cleaning**: A model that has enqueued N tokens into each of it's output
ports *must* eventually dequeue N tokens from each of it's input ports.

**No Extranenous Dependencies**: If a given output channel of an
LI-BDN simulation model has received a number of tokens no greater
than any other channel, and if the model receives all input tokens
required to compute the next output token for that channel, the model
must eventually enqueue that output token, regardless of future
external activity. Here, a model enqueueing an output token is
synonymous with the corresponding output channel "receiving" the
token.

