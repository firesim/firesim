# RocketChip-Specific Collection

This collects useful implementations for RocketChip in MIDAS.

* [RISCV frontend-server](https://github.com/riscv/riscv-fesvr) wrapper: [SW](src/main/cc/fesvr)
* [Serial Adapter](https://github.com/ucb-bar/testchipip) Endpoint Implementation: [HW](src/main/scala/endpoints/SerialWidget.scala), [Header](src/main/cc/endpoints/serial.h), [Body](src/main/cc/endpoints/serial.cc)
* [UART](https://github.com/sifive/sifive-blocks) Endpoint Implementation: [HW](src/main/scala/endpoints/UARTWidget.scala), [Header](src/main/cc/endpoints/uart.h), [Body](src/main/cc/endpoints/uart.cc)
* Simulation driver for RocketChip: [Header](src/main/cc/rocketchip.h), [Body](src/main/cc/rocketchip.cc)
