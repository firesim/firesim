// See LICENSE for license details
#ifndef __UART_H
#define __UART_H

#include <optional>
#include <signal.h>

#include "serial.h"

// The definition of the primary constructor argument for a bridge is generated
// by Golden Gate at compile time _iff_ the bridge is instantiated in the
// target. As a result, all bridge driver definitions conditionally remove
// their sources if the constructor class has been defined (the
// <cname>_struct_guard macros are generated along side the class definition.)
//
// The name of this class and its guards are always BridgeModule class name, in
// all-caps, suffixed with "_struct" and "_struct_guard" respectively.

#ifdef UARTBRIDGEMODULE_struct_guard

/**
 * Base class for callbacks handling data coming in and out a UART stream.
 */
class uart_handler {
public:
  virtual ~uart_handler() {}

  virtual std::optional<char> get() = 0;
  virtual void put(char data) = 0;
};

/**
 * Helper class which links the UART stream to either a file or PTY.
 */
class uart_fd_handler final : public uart_handler {
public:
  uart_fd_handler(int uartno);
  ~uart_fd_handler();

  std::optional<char> get() override;
  void put(char data) override;

private:
  int inputfd;
  int outputfd;
  int loggingfd;
};

class uart_t final : public bridge_driver_t {
public:
  /// Creates a bridge which interacts with standard streams or PTY.
  uart_t(simif_t *sim, const UARTBRIDGEMODULE_struct &mmio_addrs, int uartno);

  /// Creates a bridge which pulls/pushes data using a custom handler.
  uart_t(simif_t *sim,
         const UARTBRIDGEMODULE_struct &mmio_addrs,
         std::unique_ptr<uart_handler> &&handler)
      : bridge_driver_t(sim), mmio_addrs(mmio_addrs),
        handler(std::move(handler)) {}

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
  serial_data_t<char> data;
  std::unique_ptr<uart_handler> handler;

  void send();
  void recv();
};
#endif // UARTBRIDGEMODULE_struct_guard

#endif // __UART_H
