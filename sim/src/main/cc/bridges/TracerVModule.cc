// See LICENSE for license details.

#include "bridges/peek_poke.h"
#include "bridges/tracerv.h"
#include "core/bridge_driver.h"
#include "core/simif.h"
#include "core/simulation.h"

#include <iostream>

static std::vector<bool> get_contiguous(const unsigned bits,
                                        const unsigned total) {
  std::vector<bool> ret;
  for (unsigned i = 0; i < total; i++) {
    const bool value = (i < bits);
    ret.emplace_back(value);
  }

  return ret;
}

static std::vector<uint64_t> get_iaddrs(const unsigned step,
                                        const unsigned total) {
  constexpr uint64_t offset =
      1024; // should be larger than total but doesn't really matter
  std::vector<uint64_t> ret;

  for (unsigned i = 0; i < total; i++) {
    ret.emplace_back(step * offset + i);
  }

  return ret;
}

static std::string namei(const unsigned x) {
  std::stringstream ss;
  ss << "io_insns_" << x << "_iaddr";
  return ss.str();
};

static std::string nameinsn(const unsigned x) {
  std::stringstream ss;
  ss << "io_insns_" << x << "_insn";
  return ss.str();
};

static std::string namev(const unsigned x) {
  std::stringstream ss;
  ss << "io_insns_" << x << "_valid";
  return ss.str();
};

// a simple way to turn the iaddr values we use
// in this test into a 5 bit value
static uint32_t fold(const uint64_t x) {
  uint32_t a = x & 0x1f;
  uint32_t b = x >> 8;
  uint32_t c = x >> 13;
  return (a ^ b ^ c) & 0x1f;
}

class TracerVModule : public simulation_t {
private:
  peek_poke_t &peek_poke;
  tracerv_t &tracerv;

public:
  TracerVModule(const std::vector<std::string> &args, simif_t &simif)
      : simulation_t(simif, args),
        peek_poke(sim.get_registry().get_widget<peek_poke_t>()),
        tracerv(sim.get_registry().get_widget<tracerv_t>()) {

    for (auto &arg : args) {
      if (arg.find("+seed=") == 0) {
        random_seed = strtoll(arg.c_str() + 6, nullptr, 10);
        fprintf(stderr, "Using custom SEED: %ld\n", random_seed);
      }

      if (arg.find("+tracerv-expected-output=") == 0) {
        const std::string fname =
            arg.c_str() + 25; // 25 is the length of the argument to find
        expected = fopen(fname.c_str(), "w");
        if (!expected) {
          fprintf(stderr,
                  "Could not open expected test output file: %s\n",
                  fname.c_str());
          abort();
        }
      }
    }
    gen.seed(random_seed);
  }

  ~TracerVModule() override = default;

  // return the final values we will feed into MMIO
  std::pair<std::vector<uint64_t>, std::vector<bool>>
  get_final_values(const unsigned tracerv_width) {

    std::vector<bool> final_valid;
    std::vector<uint64_t> final_iaddr;
    for (unsigned i = 0; i < tracerv_width; i++) {
      final_valid.emplace_back(0);
      final_iaddr.emplace_back(0xffff);
    }

    return std::make_pair(final_iaddr, final_valid);
  }

  bool steps(const unsigned s) {
    step(s, /*blocking=*/false);
    const unsigned timeout = 10000 + s;
    bool was_done = false;
    for (unsigned i = 0; i < timeout; i++) {
      for (auto *bridge : sim.get_registry().get_all_bridges()) {
        bridge->tick();
      }

      if (peek_poke.is_done()) {
        was_done = true;
        break;
      }
    }

    if (!was_done) {
      std::cout << "Hit timeout of " << timeout
                << " tick loops afer a requested " << s << " steps"
                << std::endl;
    }

    return was_done;
  }

  using expected_t = std::pair<uint64_t, std::vector<uint64_t>>;

  std::vector<expected_t> expected_pair;

  void write_iaddr(const unsigned i, const uint64_t x) {
    peek_poke.poke(namei(i), x, true);

    // as a simplification, also write a modified version of
    // the PC value as the instruction
    peek_poke.poke(nameinsn(i), fold(x), true);
  }

  void write_valid(const unsigned i, const bool x) {
    peek_poke.poke(namev(i), x, true);
  }

  int simulation_run() override {
    tracerv.init();

    // write the header to our expected test output file
    tracerv.write_header(expected);

    // Reset the DUT.
    peek_poke.poke("reset", 1, /*blocking=*/true);
    step(1, /*blocking=*/true);
    peek_poke.poke("reset", 0, /*blocking=*/true);
    step(1, /*blocking=*/true);

    // the value of the first cycle as returned from TracerV
    const uint64_t cycle_offset = 3;

    // modified as we go
    uint64_t e_cycle = cycle_offset;

    const unsigned tracerv_width = tracerv.max_core_ipc;

    // load MMIO and capture expected outputs
    auto load = [&](std::vector<uint64_t> iad, std::vector<bool> bit) {
      std::vector<uint64_t> valid_i;
      assert(iad.size() == bit.size());
      for (unsigned i = 0; i < iad.size(); i++) {
        // std::cout << "loading " << i << " with " << iad[i] << "," << bit[i]
        // << std::endl;

        write_iaddr(i, iad[i]);
        write_valid(i, bit[i]);

        // calculate what TraverV should output, and save it
        if (bit[i]) {
          valid_i.emplace_back(iad[i]);
        }
      }

      // place instructions onto the vector 7 at a time
      for (size_t i = 0; i < valid_i.size(); i += 7) {
        auto last = std::min(valid_i.size(), i + 7);
        std::vector<uint64_t> chunk =
            std::vector<uint64_t>(valid_i.begin() + i, valid_i.begin() + last);
        expected_pair.emplace_back(std::make_pair(e_cycle, chunk));
      }

      e_cycle++;
    };

    // loop over tests. choose random valids with a simple pattern of iaddr
    // load into MMIO, and tick the system
    for (unsigned test_step = 0; test_step < get_total_trace_tests();
         test_step++) {
      const uint64_t pull = rand_next(tracerv_width + 1);

      auto pull_iaddr = get_iaddrs(test_step, tracerv_width);
      auto pull_bits = get_contiguous(pull, tracerv_width);

      load(pull_iaddr, pull_bits);
      steps(1);
    }

    const auto &[final_iaddr, final_valid] = get_final_values(tracerv_width);

    // load final values (which are not valid and thus not checked)
    load(final_iaddr, final_valid);

    tracerv.flush();

    steps(100);

    // std::cout << "trace_trigger_start " << tracerv.trace_trigger_start <<
    // "\n"; std::cout << "trigger_start_pc " << tracerv.trigger_start_pc <<
    // "\n"; std::cout << "trigger_stop_pc " << tracerv.trigger_stop_pc << "\n";

    // if testing a trigger mode, the output need to be filtered
    filter_expected_test();

    // write out a file which contains the expected output
    write_expected_file();

    return EXIT_SUCCESS;
  }

  /**
   * Pass a lambda which will be used with std::copy_if
   * this writes to expected_pair
   */
  void filter_one(std::function<bool(const expected_t beat)> fn) {
    std::vector<expected_t> filtered;
    std::copy_if(expected_pair.begin(),
                 expected_pair.end(),
                 std::back_inserter(filtered),
                 fn);
    expected_pair = std::move(filtered);
  }

  /**
   * Depending on the mode (0,1,2,3) remove expected traces
   * to match the behavior of the trigger in scala
   */
  void filter_expected_test() {

    switch (tracerv.trigger_selector) {
    case 0:
      break; // no filter
    case 1:
      // keep all instructions after a number of cycles
      filter_one([&](const expected_t beat) {
        const auto &[cycle, insns] = beat;
        return cycle >= tracerv.trace_trigger_start;
      });
      break;
    case 2:
      // match based on PC value, but delay the trigger by 1 cycle
      filter_one([&](const expected_t beat) {
        static bool keep = false;
        const auto &[cycle, insns] = beat;
        for (const auto pc : insns) {
          if (pc == tracerv.trigger_start_pc) {
            keep = true;
            return false;
          }
        }
        return keep;
      });
      break;
    case 3:
      // match based on Instruction value, trigger is not sticky
      filter_one([&](const expected_t beat) {
        const auto &[cycle, insns] = beat;
        for (const auto pc : insns) {
          // std::cout << "cyc: " << cycle << " check " << fold(pc) << " vs " <<
          // (tracerv.trigger_start_insn & tracerv.trigger_start_insn_mask) <<
          // "\n";
          if (fold(pc) ==
              (tracerv.trigger_start_insn & tracerv.trigger_start_insn_mask)) {
            return true;
          }
        }
        return false;
      });
      break;
    default:
      std::cout << "trigger_selector " << tracerv.trigger_selector
                << " not handled in filter_expected()" << std::endl;
      break;
    }
  }

  /**
   * Writes the contents of the member variable expected_pair
   * to the expected file pointer. This is done via the tracerv_t::serialize
   * function.
   */
  void write_expected_file() {
    const auto select = tracerv.trigger_selector;

    for (const auto &beat : expected_pair) {
      const auto &[cycle, insns] = beat;

      uint64_t buffer[8];
      // Zero initialization is required because hardware beats are
      // always 512 bits. The algorightm was designed such that
      // hardware sends zeros to indicate non-valid traces
      memset(buffer, 0, sizeof(buffer));

      buffer[0] = cycle;
      assert(insns.size() < 8 && "Internal test error, expected_pair cannot "
                                 "have more than 8 instructions at once");
      for (int i = 0; i < insns.size(); i++) {
        buffer[i + 1] = insns[i] | tracerv_t::valid_mask;
      }

      // because expected_pair doesn't contain the instruction value for non
      // valid instructions only the human readable output will compare
      // correctly
      tracerv_t::serialize(buffer,
                           sizeof(buffer),
                           expected,
                           /*addInstruction=*/nullptr,
                           tracerv.max_core_ipc,
                           /*human_readable=*/true,
                           /*test_output=*/false,
                           /*fireperf=*/false);
    }

    fclose(expected);
  }

  void step(uint32_t n, bool blocking) {
    if (n == 0)
      return;

    peek_poke.step(n, blocking);
  }

  /**
   * Returns the next available random number, modulo limit.
   */
  uint64_t rand_next(uint64_t limit) { return gen() % limit; }

private:
  unsigned get_total_trace_tests() const { return 128; }

  // random numbers
  uint64_t random_seed = 0;
  std::mt19937_64 gen;

  bool pass = true;
  uint64_t fail_t = 0;

  // expected test output
  FILE *expected = nullptr;
};

#define TEST_MAIN(CLASS_NAME)                                                  \
  std::unique_ptr<simulation_t> create_simulation(                             \
      const std::vector<std::string> &args, simif_t &sim) {                    \
    return std::make_unique<CLASS_NAME>(args, sim);                            \
  }

TEST_MAIN(TracerVModule)
