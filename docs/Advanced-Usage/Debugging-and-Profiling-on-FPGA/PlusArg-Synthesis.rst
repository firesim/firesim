.. _plusarg-synthesis:

PlusArg Synthesis: Runtime Modification of RTL
==============================================

Golden Gate can synthesize Rocket Chip plusargs present in Chisel/FIRRTL that would
otherwise be lost in the FPGA synthesis flow. These plusargs allow you to drive a wire
to specific value at the start of simulation.

For example:

.. code-block:: scala

    import freechips.rocketchip.util._

    val my_wire = PlusArg("set_my_wire", 0, "Description")

Then you can change the value of the ``my_wire`` during runtime instead of having to
re-synthesize the module with a different value.

Enabling PlusArg Synthesis
--------------------------

To synthesize a plusarg, you need to first use a Rocket Chip ``plusarg_reader`` module
directly, like so:

.. code-block:: scala

    import freechips.rocketchip.util._

    // see the API for plusarg_reader in the Rocket Chip source code
    // this adds a plusarg with the name 'set_my_wire', default of '0', and a width of '32'
    val my_plusarg_module = Module(new plusarg_reader("set_my_wire=%d", 0, "Description", 32))
    val my_wire = my_plusarg_module.io.out

Then you can annotate the specific plusargs you'd like to capture in your Chisel source
code like so:

.. code-block:: scala

    midas.targetutils.PlusArg(my_plusarg_module)

During compilation, Golden Gate will print the number of plusargs it has synthesized. In
the target's generated header (``FireSim-generated.const.h``), you'll find metadata for
each of the plusargs Golden Gate synthesized. This is passed as argument to a bridge
driver, which will be automatically instantiated in FireSim driver.

Runtime Arguments
-----------------

By default, the plusarg will default to the default value specified in the
``plusarg_reader`` module that was annotated. To change this value you can directly call
the runtime argument of the same name with the new value to be given at simulation
start.

For example:

**+set_my_wire=50**
    Sets the value at the start of simulation to '50'

You can set this in the ``target_config`` ``plusarg_passthrough`` field of your
``config_runtime.yaml``.
