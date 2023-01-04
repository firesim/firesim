// See LICENSE for license details.

#ifndef MIDASEXAMPLES_RISCTEST_H
#define MIDASEXAMPLES_RISCTEST_H

#include "TestHarness.h"

typedef std::vector<uint32_t> app_t;

class RiscTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  uint32_t expected = 4;
  uint64_t timeout = 10;

  void run_test() {
    app_t app;
    init_app(app);
    target_reset();
    wr(0, 0);
    for (size_t addr = 0; addr < app.size(); addr++) {
      wr(addr, app[addr]);
    }
    boot();
    uint64_t k = 0;
    do {
      tick();
      k += 1;
    } while (peek("io_valid") == 0 && k < timeout);
    expect(k < timeout, "TIME LIMIT");
    expect("io_out", expected);
  }

private:
  void wr(uint32_t addr, uint32_t data) {
    poke("io_isWr", 1);
    poke("io_boot", 0);
    poke("io_wrAddr", addr);
    poke("io_wrData", data);
    step(1);
  }
  void boot() {
    poke("io_isWr", 0);
    poke("io_boot", 1);
    step(1);
  }
  void tick() {
    poke("io_isWr", 0);
    poke("io_boot", 0);
    step(1);
  }
  uint32_t I(uint32_t op, uint32_t rc, uint32_t ra, uint32_t rb) {
    return (op << 24 | rc << 16 | ra << 8 | rb);
  }

protected:
  virtual void init_app(app_t &app) { short_app(app); }

  void short_app(app_t &app) {
    app.push_back(I(1, 1, 0, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 255, 1, 0));
  }

  void long_app(app_t &app) {
    app.push_back(I(1, 1, 0, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 2));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 3));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 4));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 5));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 6));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 7));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 8));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 9));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 1, 0, 10));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(0, 1, 1, 1));
    app.push_back(I(1, 2, 0, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 2));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 3));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 4));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 5));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 6));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 7));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 8));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 9));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(1, 2, 0, 10));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 2, 2, 1));
    app.push_back(I(0, 255, 1, 0));
  }
};

#endif // MIDASEXAMPLES_RISCTEST_H
