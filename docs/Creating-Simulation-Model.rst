Creating Simulation Model
=========================

So far, we've been using a fixed input stream model to test our device.
But, ideally, we'd like an input stream that is defined by a software model
and configurable at runtime. We'd like to put the input data in a file and
pass it in as a command-line argument. We can't do that in Chisel.
We'll have to create the model in Verilog and call out to C++ using the
Verilog DPI-C API.

First, how do we include Verilog code in a Chisel codebase? We can do this
using the Chisel BlackBox class. BlackBox modules can be used like regular
Chisel modules and have defined IO ports, but the internal implementation is
left to Verilog.

.. code-block:: scala

    class SimInputStream(w: Int) extends BlackBox(Map("DATA_BITS" -> IntParam(w))) {
      val io = IO(new Bundle {
        val clock = Input(Clock())
        val reset = Input(Bool())
        val out = Decoupled(UInt(w.W))
      })
    }

One key difference in the IO bundle definition is that the implicit ``clock``
and ``reset`` signals must be explicitly defined in a BlackBox. The BlackBox
class also takes a map that defines parameters that will be passed to the
verilog implementation. To connect the BlackBox in the test harness, we should
create a ``connectSimInput`` method in the ``HasPeripheryInputStreamModuleImp``
trait.

.. code-block:: scala

    def connectSimInput(clock: Clock, reset: Bool) {
      val sim = Module(new SimInputStream(outer.streamWidth))
      sim.io.clock := clock
      sim.io.reset := reset
      stream_in <> sim.io.out
    }

We then add a new configuration class in
``src/main/scala/example/Configs.scala`` that calls the ``connectSimInput``
method.

.. code-block:: scala


    class WithSimInputStream extends Config((site, here, up) => {
      case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
        val top = Module(LazyModule(new ExampleTopWithInputStream()(p)).module)
        top.connectSimInput(clock, reset)
        top
      }
    })

    class SimInputStreamConfig extends Config(
      new WithSimInputStream ++ new BaseExampleConfig)

Now we need to create the verilog implementation of the ``SimInputStream``
module. Make a new directory ``src/main/resources`` and add ``vsrc`` and ``csrc``
subdirectories under it.

.. code-block:: shell

    $ mkdir -p src/main/resources/{vsrc,csrc}

In the ``vsrc`` directory, create a file called ``SimInputStream.v`` and add
the following code.

.. code-block:: verilog

    import "DPI-C" function void input_stream_init
    (
        input string filename,
        input int    data_bits
    );

    import "DPI-C" function void input_stream_tick
    (
        output bit     out_valid,
        input  bit     out_ready,
        output longint out_bits
    );

    module SimInputStream #(DATA_BITS=64) (
        input                  clock,
        input                  reset,
        output                 out_valid,
        input                  out_ready,
        output [DATA_BITS-1:0] out_bits
    );

        bit __out_valid;
        longint __out_bits;
        string filename;
        int data_bits;

        reg                 __out_valid_reg;
        reg [DATA_BITS-1:0] __out_bits_reg;

        initial begin
            data_bits = DATA_BITS;
            if ($value$plusargs("instream=%s", filename)) begin
                input_stream_init(filename, data_bits);
            end
        end

        always @(posedge clock) begin
            if (reset) begin
                __out_valid = 0;
                __out_bits = 0;

                __out_valid_reg <= 0;
                __out_bits_reg <= 0;
            end else begin
                input_stream_tick(
                    __out_valid,
                    out_ready,
                    __out_bits);
                __out_valid_reg <= __out_valid;
                __out_bits_reg  <= __out_bits;
            end
        end

        assign out_valid = __out_valid_reg;
        assign out_bits  = __out_bits_reg;

    endmodule

The verilog defines its inputs and outputs to match the definition in the
Chisel BlackBox. But most of the implementation is left to C++ through the
DPI functions ``input_stream_init`` and ``input_stream_tick``. We define
these functions in a ``SimInputStream.cc`` file in the ``csrc`` directory.

.. code-block:: c++

    #include <stdio.h>
    #include <stdint.h>
    #include <stdlib.h>

    class InputStream {
      public:
        InputStream(const char *filename, int nbytes);
        ~InputStream(void);

        bool out_valid() { return !complete; }
        uint64_t out_bits() { return data; }
        void tick(bool out_ready);

      private:
        void read_next(void);
        bool complete;
        FILE *file;
        int nbytes;
        uint64_t data;
    };

    InputStream::InputStream(const char *filename, int nbytes)
    {
        this->nbytes = nbytes;
        this->file = fopen(filename, "r");
        if (this->file == NULL) {
            fprintf(stderr, "Could not open %s\n", filename);
            abort();
        }

        read_next();
    }

    InputStream::~InputStream(void)
    {
        fclose(this->file);
    }

    void InputStream::read_next(void)
    {
        int res;

        this->data = 0;

        res = fread(&this->data, this->nbytes, 1, this->file);
        if (res < 0) {
            perror("fread");
            abort();
        }

        this->complete = (res == 0);
    }

    void InputStream::tick(bool out_ready)
    {
        int res;

        if (out_valid() && out_ready)
            read_next();
    }

    InputStream *stream = NULL;

    extern "C" void input_stream_init(const char *filename, int data_bits)
    {
        stream = new InputStream(filename, data_bits/8);
    }

    extern "C" void input_stream_tick(
            unsigned char *out_valid,
            unsigned char out_ready,
            long long     *out_bits)
    {
        stream->tick(out_ready);
        *out_valid = stream->out_valid();
        *out_bits  = stream->out_bits();
    }

In the C++ file, we implement an ``InputStream`` class that takes a file name
as its argument. It opens the file and reads ``nbytes`` from it for every
ready-valid handshake. The ``input_stream_init`` function constructs an
``InputStream`` class and assigns it to a global pointer. The
``input_stream_tick`` function updates the state by calling the ``tick``
method, passing in the inputs from verilog. It then assigns values to the
verilog outputs.

You can now build this new configuration in VCS.

.. code-block:: shell

    $ cd vsim
    $ make CONFIG=SimInputStreamConfig

Now create a file that can be used as the input stream data. Just getting
random bytes from ``/dev/urandom`` would work. Pass this to your simulation
through the ``+instream=`` flag, and you should see the data get printed
out in the ``input-stream.riscv`` test.

.. code-block:: shell

    $ dd if=/dev/urandom of=instream.img bs=32 count=1
    $ hexdump instream.img
    0000000 189b f12a 1cc1 9eb5 b65d bbef 96b6 4949
    0000010 f8c8 636c 76fe 15f3 0665 0ef9 8c5d 3011
    0000020
    $ ./simv-example-SimInputStreamConfig +instream=instream.img ../tests/input-stream.riscv
    9eb51cc1f12a189b
    494996b6bbefb65d
    15f376fe636cf8c8
    30118c5d0ef90665
