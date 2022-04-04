.. _debugging-hanging-simulators:

Debugging a Hanging Simulator
=============================

A common symptom of a failing simulation is that appears to
hang. Debugging this is especially daunting in FireSim because it's not immediately
obvious if it's a bug in the target, or somewhere in the host. To make it easier to
identify the problem, the simulation driver includes a polling watchdog that
tracks for simulation progress, and periodically updates an output file,
``heartbeat.csv``, with a target cycle count and a timestamp. When debugging
these issues, we always encourage the use of metasimulation to try
reproducing the failure if possible. We outline three common cases in the
section below.


Case 1: Target hang.
++++++++++++++++++++++++++++

**Symptoms:** There is no output from the target (i.e., the uartlog
might cease), but simulated time continues to advance (``heartbeat.csv`` will
be periodically updated). Simulator instrumentation (TracerV, printf) may
continue to produce new output.

**Causes:** Typically, a bug in the target RTL. However, bridge bugs leading to
erroneous token values will also produce this behavior.

**Next steps:** You can deploy the full suite of FireSim's debugging tools for
failures of this nature, since assertion synthesis, printf synthesis, and other
target-side features still function. Assume there is a bug in the target RTL
and trace back the failure to a bridge if applicable.


Case 2: Simulator hang due to FPGA-side token starvation.
+++++++++++++++++++++++++++++++++++++++++++++++++++++++++

**Symptoms:** The driver's main loop spins freely, as no bridge gets new
work to do.  As a result, the polling interval quickly elapses and the
simulation is torn down due to a lack of forward progress.

**Causes:** Generally, a bug in a bridge implementation (ex. the BridgeModule has accidentally dequeued a
token without producing a new output token; the BridgeModule is waiting on a driver interaction that never occurs).

**Next steps:** These are the trickiest to solve. Try to identify the bridge that's
responsible by removing unnecessary ones, using an AutoILA, and adding printfs
to BridgeDriver sources.  Target-side debugging utilities may be used to
identify problematic target behavior, but tend not to be useful for identifying
the root cause.

Case 3: Simulator hang due to driver-side deadlock.
+++++++++++++++++++++++++++++++++++++++++++++++++++

**Symptoms:** The loss of all output, notably, ``heartbeat.csv`` ceases to be further updated.

**Causes:** Generally, a bridge driver bug. For example, the driver may be busy waiting on
some output from the FPGA, but the FPGA-hosted part of the simulator has
stalled waiting for tokens.

**Next Steps:** Identify the buggy driver using printfs or attaching to the
running simulator using GDB.


Simulator Heartbeat PlusArgs
++++++++++++++++++++++++++++

``+heartbeat-polling-interval=<int>``: Specifies the number of round trips through
the simulator main loop before polling the FPGA's target cycle counter. Disable
the heartbeat by setting this to -1.
