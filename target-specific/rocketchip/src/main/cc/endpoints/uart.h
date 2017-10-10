// See LICENSE for license details.

#ifndef __UART_H
#define __UART_H

#include "serial.h"
#include <signal.h>

class uart_t: public endpoint_t
{
public:
  uart_t(simif_t* sim);
  void send();
  void recv();
  virtual void tick();
  virtual bool done() { return read(UARTWIDGET_0(done)); }
  virtual bool stall() { return read(UARTWIDGET_0(stall)); }

private:
  serial_data_t<char> data;
};

#endif // __UART_H
