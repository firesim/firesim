//See LICENSE for license details.

#include "simif.h"

class IdleDriver: virtual simif_t
{
public:
  IdleDriver(int argc, char** argv) {}
  void run() {
      step(128 * 1024);
    }
};

class ClockMux_t: public IdleDriver
{
public:
    using IdleDriver::IdleDriver;
};

class ClockDivider_t: public IdleDriver
{
public:
    using IdleDriver::IdleDriver;
};

class ClockGateExample_t: public IdleDriver
{
public:
    using IdleDriver::IdleDriver;
};
