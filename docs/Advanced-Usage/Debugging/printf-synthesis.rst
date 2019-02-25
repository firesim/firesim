Printf Synthesis
===================

MIDAS can synthesize printfs present in FIRRTL (implemented as ``printf``
statements) that would otherwise be lost in the FPGA synthesis flow. Rocket and
BOOM have printfs of their commit logs and other useful transaction
streams.

::

    C0:        409 [1] pc=[008000004c] W[r10=0000000000000000][1] R[r 0=0000000000000000] R[r20=0000000000000003] inst=[f1402573] csrr    a0, mhartid
    C0:        410 [0] pc=[008000004c] W[r 0=0000000000000000][0] R[r 0=0000000000000000] R[r20=0000000000000003] inst=[f1402573] csrr    a0, mhartid
    C0:        411 [0] pc=[008000004c] W[r 0=0000000000000000][0] R[r 0=0000000000000000] R[r20=0000000000000003] inst=[f1402573] csrr    a0, mhartid
    C0:        412 [1] pc=[0080000050] W[r 0=0000000000000000][0] R[r10=0000000000000000] R[r 0=0000000000000000] inst=[00051063] bnez    a0, pc + 0
    C0:        413 [1] pc=[0080000054] W[r 5=0000000080000054][1] R[r 0=0000000000000000] R[r 0=0000000000000000] inst=[00000297] auipc   t0, 0x0
    C0:        414 [1] pc=[0080000058] W[r 5=0000000080000064][1] R[r 5=0000000080000054] R[r16=0000000000000003] inst=[01028293] addi    t0, t0, 16
    C0:        415 [1] pc=[008000005c] W[r 0=0000000000010000][1] R[r 5=0000000080000064] R[r 5=0000000080000064] inst=[30529073] csrw    mtvec, t0

Synthesizing these printfs lets you capture the same logs on a running FireSim instance.

Enabling Printf Synthesis
----------------------------

To synthesize a printf, in your Chisel source you need to annotate the specific
printfs you'd like to capture.  Presently, due to a limitation in Chisel and
FIRRTL's annotation system, you need to annotate the arguments to the printf, not the printf itself,
like so:

::

    printf(midas.targetutils.SynthesizePrintf("x%d p%d 0x%x\n", rf_waddr, rf_waddr, rf_wdata))

Be judicious, as synthesizing many, frequently active printfs, will slow down your simulator. 

Once your printfs have been annotated, to enable printf synthesis add the ``WithPrintfSynthesis`` Config to your
PLATFORM_CONFIG in SimConfigs.scala.  During compilation, MIDAS will print the
number of printfs it's synthesized.  In the target's generated header
(``<DESIGN>-const.h``), you'll find metadata for each of the printfs MIDAS synthesized.
This is passed as argument to the constructor of the ``synthesized_prints_t``
endpoint driver, which will be automatically instantiated in FireSim driver.

Runtime Arguments
-----------------
**+print-file**
    Specifies the file into which the synthesized printf log should written.

**+print-start**
    Specifies the target-cycle at which the printf trace should be captured in the
    simulator. Since capturing high-bandwidth printf traces will slow down
    simulation, this allows the user to reach the region-of-interest at full simulation speed.

**+print-end**
    Specifies the target cycle at which to stop pulling the synthesized print
    trace from the simulator.

**+print-binary**
    By default, a captured printf trace will be written to file formatted
    as it would be emitted by a software RTL simulator. Setting this dumps the
    raw binary coming off the FPGA instead, improving simulation rate.

**+print-no-cycle-prefix**
    (Formatted output only) This removes the cycle prefix from each printf to
    save bandwidth in cases where the printf already includes a cycle field. In
    binary-output mode, since the target cycle is implicit in the token stream,
    this flag has no effect.

Related Publications
--------------------

Printf synthesis was first presented in our FPL2018 paper, `DESSERT
<https://people.eecs.berkeley.edu/~biancolin/papers/dessert-fpl18.pdf>`_.
