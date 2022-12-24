// See LICENSE for license details

#ifndef __UART_H
#define __UART_H

#include <optional>
#include <signal.h>

#include "serial.h"

/**
 * Structure carrying the addresses of all fixed MMIO ports.
 *
 * This structure is instantiated when all bridges are populated based on
 * the target configuration.
 */
typedef struct UARTBRIDGEMODULE_struct {
  uint64_t out_bits;
  uint64_t out_valid;
  uint64_t out_ready;
  uint64_t in_bits;
  uint64_t in_valid;
  uint64_t in_ready;
} UARTBRIDGEMODULE_struct;

#ifdef UARTBRIDGEMODULE_checks
UARTBRIDGEMODULE_checks;
#endif // UARTBRIDGEMODULE_checks

/**
 * Base class for callbacks handling data coming in and out a UART stream.
 */
class uart_handler {
public:
  virtual ~uart_handler() {}

  virtual std::optional<char> get() = 0;
  virtual void put(char data) = 0;
};

class uart_t final : public bridge_driver_t {
public:
  /// Creates a bridge which interacts with standard streams or PTY.
  uart_t(simif_t *sim,
         const std::vector<std::string> &args,
         const UARTBRIDGEMODULE_struct &mmio_addrs,
         int uartno);

  ~uart_t();

  void tick();
  // Our UART bridge's initialzation and teardown procedures don't
  // require interaction with the FPGA (i.e., MMIO), and so we don't need
  // to define init and finish methods (we can do everything in the
  // ctor/dtor)
  void init(){};
  void finish(){};

  // Our UART bridge never calls for the simulation to terminate
  bool terminate() { return false; }

  // ... and thus, never returns a non-zero exit code
  int exit_code() { return 0; }

private:
  const UARTBRIDGEMODULE_struct mmio_addrs;
  std::unique_ptr<uart_handler> handler;

  serial_data_t<char> data;

  void send();
  void recv();
};

#endif // __UART_H
