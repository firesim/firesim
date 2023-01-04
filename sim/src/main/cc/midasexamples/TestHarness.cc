#include <cinttypes>

#include "TestHarness.h"

static const char *blocking_fail =
    "The test environment has starved the simulator, preventing forward "
    "progress.";

TestHarness::TestHarness(const std::vector<std::string> &args, simif_t *simif)
    : simulation_t(args), simif(simif) {
  for (auto arg : args) {
    if (arg.find("+seed=") == 0) {
      random_seed = strtoll(arg.c_str() + 6, NULL, 10);
      std::cerr << "Using custom SEED: " << random_seed << std::endl;
    }
    continue;
  }
  random.seed(random_seed);
}

void TestHarness::step(uint32_t n, bool blocking) {
  if (n == 0)
    return;
  // take steps
  if (log) {
    std::cerr << "* STEP " << n << " -> " << t + n << " *" << std::endl;
  }
  simif->take_steps(n, blocking);
  t += n;
}

void TestHarness::target_reset(int pulse_length) {
  poke("reset", 1);
  step(pulse_length, true);
  poke("reset", 0);
}

void TestHarness::poke(std::string_view id, uint32_t value, bool blocking) {
  peek_poke->poke(id, value, blocking);

  if (peek_poke->timeout()) {
    if (log) {
      std::cerr << "* FAIL : POKE on " << simif->get_target_name() << "." << id
                << " has timed out. " << blocking_fail << " : FAIL"
                << std::endl;
    }
    throw;
  }

  if (log) {
    std::cerr << "* POKE " << simif->get_target_name() << "." << id << " <- 0x"
              << std::hex << value << " *" << std::endl;
  }
}

uint32_t TestHarness::peek(std::string_view id, bool blocking) {
  uint32_t value = peek_poke->peek(id, blocking);

  if (peek_poke->timeout()) {
    if (log) {
      std::cerr << "* FAIL : PEEK on " << simif->get_target_name() << "." << id
                << " has timed out. " << blocking_fail << " : FAIL"
                << std::endl;
    }
    throw;
  }

  if (peek_poke->unstable()) {
    std::cerr << "* WARNING : The following peek is on an unstable value!"
              << std::endl;
  }

  if (log) {
    std::cerr << "* PEEK " << simif->get_target_name() << "." << id << " <- 0x"
              << std::hex << value << " *" << std::endl;
  }
  return value;
}

void TestHarness::poke(std::string_view id, mpz_t &value) {
  if (log) {
    std::unique_ptr<char[]> v_str(mpz_get_str(NULL, 16, value));
    std::cerr << "* POKE " << simif->get_target_name() << "." << id << " <- 0x"
              << std::hex << v_str.get() << " *" << std::endl;
  }
  peek_poke->poke(id, value);
}

void TestHarness::peek(std::string_view id, mpz_t &value) {
  peek_poke->peek(id, value);
  if (log) {
    std::unique_ptr<char[]> v_str(mpz_get_str(NULL, 16, value));
    std::cerr << "* PEEK " << simif->get_target_name() << "." << id << " <- 0x"
              << std::hex << v_str.get() << " *" << std::endl;
  }
}

bool TestHarness::expect(std::string_view id, uint32_t expected) {
  uint32_t value = peek(id);
  bool pass = value == expected;
  if (log) {
    std::cerr << "* EXPECT " << simif->get_target_name() << "." << id
              << " -> 0x" << std::hex << value << " ?= 0x" << std::hex
              << expected << " : " << (pass ? "PASS" : "FAIL") << std::endl;
  }
  return expect(pass, NULL);
}

bool TestHarness::expect(bool nowPass, const char *s) {
  if (log && s) {
    std::cerr << "* " << s << " : " << (pass ? "PASS" : "FAIL") << " *"
              << std::endl;
  }

  if (pass && !nowPass)
    fail_t = t;

  pass &= nowPass;
  return nowPass;
}

bool TestHarness::expect(std::string_view id, mpz_t &expected) {
  mpz_t value;
  mpz_init(value);
  peek(id, value);
  bool pass = mpz_cmp(value, expected) == 0;
  if (log) {
    std::unique_ptr<char[]> v_str(mpz_get_str(NULL, 16, value));
    std::unique_ptr<char[]> e_str(mpz_get_str(NULL, 16, expected));

    std::cerr << "* EXPECT " << simif->get_target_name() << "." << id
              << " -> 0x" << v_str.get() << " ?= 0x" << e_str.get() << " : "
              << (pass ? "PASS" : "FAIL") << std::endl;
  }
  mpz_clear(value);
  return expect(pass, NULL);
}

int TestHarness::teardown() {
  std::cerr << "[" << simif->get_target_name() << "]";
  std::cerr << (pass ? "PASS" : "FAIL") << " Test";
  if (!pass) {
    std::cerr << " at cycle " << fail_t;
  }
  std::cerr << std::endl;
  std::cerr << "SEED: " << random_seed << std::endl;
  return pass ? EXIT_SUCCESS : EXIT_FAILURE;
}
