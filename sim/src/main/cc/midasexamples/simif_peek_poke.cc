// See LICENSE for license details.

#include <cinttypes>

#include "simif_peek_poke.h"

simif_peek_poke_t::simif_peek_poke_t() {
  PEEKPOKEBRIDGEMODULE_0_substruct_create;
  this->defaultiowidget_mmio_addrs = PEEKPOKEBRIDGEMODULE_0_substruct;
}

void simif_peek_poke_t::target_reset(int pulse_length) {
  poke(reset, 1);
  this->step(pulse_length, true);
  poke(reset, 0);
}

void simif_peek_poke_t::step(uint32_t n, bool blocking) {
  if (n == 0)
    return;
  // take steps
  if (log)
    fprintf(stderr, "* STEP %d -> %lu *\n", n, (t + n));
  take_steps(n, blocking);
  t += n;
}

void simif_peek_poke_t::poke(size_t id, uint32_t value, bool blocking) {
  if (blocking && !wait_on_ready(10.0)) {
    if (log) {
      std::string fmt = "* FAIL : POKE on %s.%s has timed out. %s : FAIL\n";
      fprintf(stderr,
              fmt.c_str(),
              TARGET_NAME,
              (const char *)INPUT_NAMES[id],
              blocking_fail.c_str());
    }
    throw;
  }
  if (log)
    fprintf(stderr,
            "* POKE %s.%s <- 0x%x *\n",
            TARGET_NAME,
            INPUT_NAMES[id],
            value);
  write(INPUT_ADDRS[id], value);
}

uint32_t simif_peek_poke_t::peek(size_t id, bool blocking) {
  if (blocking && !wait_on_ready(10.0)) {
    if (log) {
      std::string fmt = "* FAIL : PEEK on %s.%s has timed out. %s : FAIL\n";
      fprintf(stderr,
              fmt.c_str(),
              TARGET_NAME,
              (const char *)INPUT_NAMES[id],
              blocking_fail.c_str());
    }
    throw;
  }

  bool peek_may_be_unstable = blocking && !wait_on_stable_peeks(0.1);
  if (log && peek_may_be_unstable)
    fprintf(stderr,
            "* WARNING : The following peek is on an unstable value!\n");
  uint32_t value = read(((unsigned int *)OUTPUT_ADDRS)[id]);
  if (log)
    fprintf(stderr,
            "* PEEK %s.%s -> 0x%x *\n",
            TARGET_NAME,
            (const char *)OUTPUT_NAMES[id],
            value);
  return value;
}

uint32_t simif_peek_poke_t::sample_value(size_t id) { return peek(id, false); }

bool simif_peek_poke_t::expect(size_t id, uint32_t expected) {
  uint32_t value = peek(id);
  bool pass = value == expected;
  if (log) {
    fprintf(stderr,
            "* EXPECT %s.%s -> 0x%x ?= 0x%x : %s\n",
            TARGET_NAME,
            (const char *)OUTPUT_NAMES[id],
            value,
            expected,
            pass ? "PASS" : "FAIL");
  }
  return expect(pass, NULL);
}

bool simif_peek_poke_t::expect(bool pass, const char *s) {
  if (log && s)
    fprintf(stderr, "* %s : %s *\n", s, pass ? "PASS" : "FAIL");
  if (this->pass && !pass)
    fail_t = t;
  this->pass &= pass;
  return pass;
}

void simif_peek_poke_t::poke(size_t id, mpz_t &value) {
  if (log) {
    char *v_str = mpz_get_str(NULL, 16, value);
    fprintf(stderr,
            "* POKE %s.%s <- 0x%s *\n",
            TARGET_NAME,
            INPUT_NAMES[id],
            v_str);
    free(v_str);
  }
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(NULL, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < INPUT_CHUNKS[id]; i++) {
    write(INPUT_ADDRS[id] + (i * sizeof(uint32_t)), i < size ? data[i] : 0);
  }
}

void simif_peek_poke_t::peek(size_t id, mpz_t &value) {
  const size_t size = (const size_t)OUTPUT_CHUNKS[id];
  uint32_t data[size];
  for (size_t i = 0; i < size; i++) {
    data[i] = read((size_t)OUTPUT_ADDRS[id] + (i * sizeof(uint32_t)));
  }
  mpz_import(value, size, -1, sizeof(uint32_t), 0, 0, data);
  if (log) {
    char *v_str = mpz_get_str(NULL, 16, value);
    fprintf(stderr,
            "* PEEK %s.%s -> 0x%s *\n",
            TARGET_NAME,
            (const char *)OUTPUT_NAMES[id],
            v_str);
    free(v_str);
  }
}

bool simif_peek_poke_t::expect(size_t id, mpz_t &expected) {
  mpz_t value;
  mpz_init(value);
  peek(id, value);
  bool pass = mpz_cmp(value, expected) == 0;
  if (log) {
    char *v_str = mpz_get_str(NULL, 16, value);
    char *e_str = mpz_get_str(NULL, 16, expected);
    fprintf(stderr,
            "* EXPECT %s.%s -> 0x%s ?= 0x%s : %s\n",
            TARGET_NAME,
            (const char *)OUTPUT_NAMES[id],
            v_str,
            e_str,
            pass ? "PASS" : "FAIL");
    free(v_str);
    free(e_str);
  }
  mpz_clear(value);
  return expect(pass, NULL);
}

int simif_peek_poke_t::teardown() {
  record_end_times();
  fprintf(stderr, "[%s] %s Test", pass ? "PASS" : "FAIL", TARGET_NAME);
  if (!pass) {
    fprintf(stdout, " at cycle %" PRIu64, fail_t);
  }
  fprintf(stderr, "\nSEED: %ld\n", get_seed());
  this->print_simulation_performance_summary();

  this->host_finish();

  return pass ? EXIT_SUCCESS : EXIT_FAILURE;
}
