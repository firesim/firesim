// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

#include "verilated.h"
#if VM_TRACE
#include "verilated_vcd_c.h"
#include <memory>
#endif
#include <fcntl.h>
#include <getopt.h>
#include <iostream>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

// Originally from Rocket-Chip, with RISC-V specific stuff stripped out

// For option parsing, which is split across this file, Verilog, a few external
// files must be pulled in. The list of files and what they provide is
// enumerated:
//
// Biancolin: This will be useful later.
// $(ROCKETCHIP_DIR)/generated-src(-debug)?/$(CONFIG).plusArgs:
//   defines:
//     - PLUSARG_USAGE_OPTIONS
//   variables:
//     - static const char * verilog_plusargs

static uint64_t trace_count = 0;
bool verbose;
bool done_reset;

// void handle_sigterm(int sig)
//{
//  Biancolin: //TODO
//}

double sc_time_stamp() { return trace_count; }

extern "C" int vpi_get_vlog_info(void *arg) { return 0; }

static void usage(const char *program_name) {
  printf("Usage: %s [VERILOG PLUSARG]...\n", program_name);
  fputs("\
Run a BINARY on the Rocket Chip emulator.\n\
\n\
Mandatory arguments to long options are mandatory for short options too.\n\
\n\
EMULATOR OPTIONS\n\
  -c, --cycle-count        Print the cycle count before exiting\n\
       +cycle-count\n\
  -h, --help               Display this help and exit\n\
  -m, --max-cycles=CYCLES  Kill the emulation after CYCLES\n\
       +max-cycles=CYCLES\n\
  -s, --seed=SEED          Use random number seed SEED\n\
                           automatically.\n\
  -V, --verbose            Enable all Chisel printfs (cycle-by-cycle info)\n\
       +verbose\n\
",
        stdout);
#if VM_TRACE == 0
  fputs("\
\n\
EMULATOR DEBUG OPTIONS (only supported in debug build -- try `make debug`)\n",
        stdout);
#endif
  fputs("\
  -v, --vcd=FILE,          Write vcd trace to FILE (or '-' for stdout)\n\
  -x, --dump-start=CYCLE   Start VCD tracing at CYCLE\n\
       +dump-start\n\
",
        stdout);
  // fputs("\n" PLUSARG_USAGE_OPTIONS, stdout);
}

int main(int argc, char **argv) {
  unsigned random_seed = (unsigned)time(NULL) ^ (unsigned)getpid();
  uint64_t max_cycles = -1;
  int ret = 0;
  bool print_cycles = false;
  // Port numbers are 16 bit unsigned integers.
#if VM_TRACE
  FILE *vcdfile = NULL;
  uint64_t start = 0;
#endif
  int verilog_plusargs_legal = 1;

  while (1) {
    static struct option long_options[] = {
      {"cycle-count", no_argument, 0, 'c'},
      {"help", no_argument, 0, 'h'},
      {"max-cycles", required_argument, 0, 'm'},
      {"seed", required_argument, 0, 's'},
      {"rbb-port", required_argument, 0, 'r'},
#if VM_TRACE
      {"vcd", required_argument, 0, 'v'},
      {"dump-start", required_argument, 0, 'x'},
#endif
      {"verbose", no_argument, 0, 'V'}
    };
    int option_index = 0;
#if VM_TRACE
    int c =
        getopt_long(argc, argv, "-chm:s:r:v:Vx:", long_options, &option_index);
#else
    int c = getopt_long(argc, argv, "-chm:s:r:V", long_options, &option_index);
#endif
    if (c == -1)
      break;
  retry:
    switch (c) {
    // Process long and short EMULATOR options
    case '?':
      usage(argv[0]);
      return 1;
    case 'c':
      print_cycles = true;
      break;
    case 'h':
      usage(argv[0]);
      return 0;
    case 'm':
      max_cycles = atoll(optarg);
      break;
    case 's':
      random_seed = atoi(optarg);
      break;
    case 'V':
      verbose = true;
      break;
#if VM_TRACE
    case 'v': {
      vcdfile = strcmp(optarg, "-") == 0 ? stdout : fopen(optarg, "w");
      if (!vcdfile) {
        std::cerr << "Unable to open " << optarg << " for VCD write\n";
        return 1;
      }
      break;
    }
    case 'x':
      start = atoll(optarg);
      break;
#endif
    // Process legacy '+' EMULATOR arguments by replacing them with
    // their getopt equivalents
    case 1: {
      std::string arg = optarg;
      if (arg.substr(0, 1) != "+") {
        optind--;
        goto done_processing;
      }
      if (arg == "+verbose")
        c = 'V';
      else if (arg.substr(0, 12) == "+max-cycles=") {
        c = 'm';
        optarg = optarg + 12;
      }
#if VM_TRACE
      else if (arg.substr(0, 12) == "+dump-start=") {
        c = 'x';
        optarg = optarg + 12;
      }
#endif
      else if (arg.substr(0, 12) == "+cycle-count")
        c = 'c';
      // If we don't find a legacy '+' EMULATOR argument, it still could be
      // a VERILOG_PLUSARG and not an error.
      // else if (verilog_plusargs_legal) {
      //  const char ** plusarg = &verilog_plusargs[0];
      //  int legal_verilog_plusarg = 0;
      //  while (*plusarg && (legal_verilog_plusarg == 0)){
      //    if (arg.substr(1, strlen(*plusarg)) == *plusarg) {
      //      legal_verilog_plusarg = 1;
      //    }
      //    plusarg ++;
      //  }
      //  if (!legal_verilog_plusarg) {
      //    verilog_plusargs_legal = 0;
      //  } else {
      //    c = 'P';
      //  }
      //  goto retry;
      //}
      // Not a recongized plus-arg
      else {
        std::cerr << argv[0] << ": invalid plus-arg (Verilog or HTIF) \"" << arg
                  << "\"\n";
        c = '?';
      }
      goto retry;
    }
    case 'P':
      break; // Nothing to do here, Verilog PlusArg
    default:
      c = '?';
      goto retry;
    }
  }

done_processing:
  if (verbose)
    fprintf(stderr, "using random seed %u\n", random_seed);

  srand(random_seed);
  srand48(random_seed);

  Verilated::randReset(2);
  Verilated::commandArgs(argc, argv);
  TEST_HARNESS *tile = new TEST_HARNESS;

#if VM_TRACE
  Verilated::traceEverOn(true); // Verilator must compute traced signals
  std::unique_ptr<VerilatedVcdFILE> vcdfd(new VerilatedVcdFILE(vcdfile));
  std::unique_ptr<VerilatedVcdC> tfp(new VerilatedVcdC(vcdfd.get()));
  if (vcdfile) {
    tile->trace(tfp.get(), 99); // Trace 99 levels of hierarchy
    tfp->open("");
  }
#endif

  // signal(SIGTERM, handle_sigterm);

  bool dump;
  // reset for several cycles to handle pipelined reset
  for (int i = 0; i < 10; i++) {
    tile->reset = 1;
    tile->clock = 0;
    tile->eval();
#if VM_TRACE
    dump = tfp && trace_count >= start;
    if (dump)
      tfp->dump(static_cast<vluint64_t>(trace_count * 2));
#endif
    tile->clock = 1;
    tile->eval();
#if VM_TRACE
    if (dump)
      tfp->dump(static_cast<vluint64_t>(trace_count * 2 + 1));
#endif
    trace_count++;
  }
  tile->reset = 0;
  done_reset = true;

  while (!tile->io_success && trace_count < max_cycles) {
    tile->clock = 0;
    tile->eval();
#if VM_TRACE
    dump = tfp && trace_count >= start;
    if (dump)
      tfp->dump(static_cast<vluint64_t>(trace_count * 2));
#endif

    tile->clock = 1;
    tile->eval();
#if VM_TRACE
    if (dump)
      tfp->dump(static_cast<vluint64_t>(trace_count * 2 + 1));
#endif
    trace_count++;
  }

#if VM_TRACE
  if (tfp)
    tfp->close();
  if (vcdfile)
    fclose(vcdfile);
#endif

  if (trace_count == max_cycles) {
    fprintf(
        stderr,
        "*** FAILED *** via trace_count (timeout, seed %d) after %ld cycles\n",
        random_seed,
        trace_count);
    ret = 2;
  } else if (verbose || print_cycles) {
    fprintf(stderr, "Completed after %ld cycles\n", trace_count);
  }

  if (tile)
    delete tile;
  return ret;
}
