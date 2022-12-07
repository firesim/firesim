// See LICENSE for license details.

#ifndef RTLSIM
#include "simif_f1.h"
#define SIMIF simif_f1_t
#else
#include "simif_emul.h"
#define SIMIF simif_emul_t
#endif

#include "bridges/uart.h"
#include "simif_peek_poke.h"

class UARTModuleTest final : public simif_peek_poke_t {
public:
  class Handler final : public uart_handler {
  public:
    Handler(UARTModuleTest &test) { test.handler = this; }

    ~Handler() {
      // Check that the input and output buffers are equal.
      if (in_buffer != out_buffer) {
        fprintf(stderr,
                "Buffer mismatch:\n  %s\n  %s\n",
                in_buffer.c_str(),
                out_buffer.c_str());
        abort();
      }
    }

    std::optional<char> get() override {
      if (in_index >= in_buffer.size())
        return std::nullopt;
      return in_buffer[in_index++];
    }

    void put(char data) override { out_buffer += data; }

    const std::string in_buffer = "We are testing the UART bridge";
    std::string out_buffer;
    size_t in_index = 0;
  };

  Handler *handler;

  UARTModuleTest(simif_t &simif)
      : simif_peek_poke_t(&simif, PEEKPOKEBRIDGEMODULE_0_substruct_create),
        uart(std::make_unique<uart_t>(&simif,
                                      UARTBRIDGEMODULE_0_substruct_create,
                                      std::make_unique<Handler>(*this))) {}

  void run() {
    // Initialise the UART bridge.
    uart->init();

    // Reset the device.
    poke(reset, 1);
    step(1);
    poke(reset, 0);
    step(1);

    // Tick until the out buffer is filled in.
    step(300000, false);
    for (unsigned i = 0; i < 100000 && !simif->done(); ++i) {
      uart->tick();
    }

    // Cleanup.
    uart->finish();
  }

private:
  std::unique_ptr<uart_t> uart;
};

int main(int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  SIMIF simif(args);
  simif.init(argc, argv);

  UARTModuleTest test(simif);
  test.run();
  return test.teardown();
}
