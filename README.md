# FireSim: Fast and Effortless FPGA-accelerated Hardware Simulation with On-Prem and Cloud Flexibility

![FireSim Documentation Status](https://readthedocs.org/projects/firesim/badge/)
![Github Actions Status](https://github.com/firesim/firesim/actions/workflows/firesim-run-tests.yml/badge.svg)

| We're running the First FireSim and Chipyard User/Developer Workshop at ASPLOS 2023 on March 26, 2023! This workshop will feature a full-day of submitted talks from users and developers in the FireSim and Chipyard community. Learn more and **submit your work** on the [2023 Workshop Page](https://fires.im/workshop-2023/)! |
|--------|

## Contents

1. [Using FireSim](#using-firesim)
2. [What is FireSim?](#what-is-firesim)
3. [What can I simulate with FireSim?](#what-can-i-simulate-with-firesim)
4. [Need help?](#need-help)
5. [Contributing](#contributing)
6. [Publications](#publications)

## Using FireSim

To get started with FireSim, you can find our extensive documentation and getting-started guide at
[docs.fires.im](https://docs.fires.im). The FireSim codebase is open-source at
[github.com/firesim/firesim](https://github.com/firesim/firesim) and we welcome pull requests and issues.
You can also get help from the FireSim user community on our [User Forum](https://groups.google.com/forum/#!forum/firesim). 
Additionally, we frequently run tutorials at various conferences
and events; for overview purposes, you can find the most recent slide decks at [fires.im/tutorial-recent](https://fires.im/tutorial-recent) (you should still follow [docs.fires.im](https://docs.fires.im) for the most up to date getting-started guide).

Another good overview from a recent seminar (in video format) can be found [on YouTube](https://www.youtube.com/watch?v=UlYOsRBhtY8).

## What is FireSim?

FireSim is an [open-source](https://github.com/firesim/firesim)
FPGA-accelerated full-system hardware simulation platform that makes
it easy to validate, profile, and debug RTL hardware implementations
at 10s to 100s of MHz. FireSim simplifies co-simulating 
ASIC RTL with cycle-accurate hardware and software models for other system components (e.g. I/Os). FireSim can productively 
scale from individual SoC simulations hosted on on-prem FPGAs (e.g., a single Xilinx Alveo board attached to a desktop) 
to massive datacenter-scale simulations harnessing hundreds of cloud FPGAs (e.g., on Amazon EC2 F1).

**Who's using and developing FireSim?** FireSim users across academia and industry have written over 25 papers using FireSim in many areas, including computer architecture, systems, networking, circuits, security, and more (see the [Publications page](https://fires.im/publications/)). FireSim has also been used in the development of shipping commercial silicon. FireSim was originally developed in the [Electrical Engineering and Computer Sciences
Department][eecs] at the [University of California, Berkeley][berkeley], but now has industrial and academic contributors from all over the world.

You can learn more about FireSim in the following places:

* **FireSim website**: https://fires.im
* **FireSim ISCA 2018 Paper**: [Paper PDF](https://sagark.org/assets/pubs/firesim-isca2018.pdf) | [IEEE Xplore](https://ieeexplore.ieee.org/document/8416816) | [ACM DL](https://dl.acm.org/citation.cfm?id=3276543) | [BibTeX](https://sagark.org/assets/pubs/firesim-isca2018.bib.txt) | Selected as one of IEEE Micro’s “Top Picks from Computer Architecture Conferences, 2018”.
* **FireSim documentation**: https://docs.fires.im
* **FireSim (+Chipyard) Tutorial**: https://fires.im/tutorial/
* **Scala API Documentation**: https://fires.im/firesim/latest/api/
* **Two-minute lightning talk from ISCA 2018** (FireSim simulating a datacenter): [YouTube](https://www.youtube.com/watch?v=4XwoSe5c8lY)
* **Chisel Community Conference Tutorial**: [YouTube](https://www.youtube.com/watch?v=S3OriQnJXYQ)
* **Updates/News**: [Changelog](/CHANGELOG.md) | [FireSim Blog](https://fires.im/blog/) | [Twitter](https://twitter.com/firesimproject)

## What can I simulate with FireSim?

FireSim can simulate arbitrary hardware designs written in
[Chisel](https://chisel.eecs.berkeley.edu) or Verilog.
With FireSim, users can write their own RTL (processors, accelerators, etc.) and
run it at near-FPGA-prototype speeds on cloud or on-prem FPGAs, while obtaining
performance results that match an ASIC implementation of the same design. 
Depending on the hardware design and the simulation scale,
FireSim simulations run at 10s to 100s of MHz. Users can also integrate
custom software models for components that they don't need or want to write as RTL.
To help construct a closed and deterministic simulated environment around a design, FireSim includes
validated and high-performance HW/SW models for I/Os like DRAM, Ethernet, Disks, UART, and more.
The [User Publications page][userpubs] links to a selection of papers written by FireSim users.

FireSim was originally developed to simulate datacenters by combining
open RTL for RISC-V processors with a custom cycle-accurate network simulation.
By default, FireSim provides all the RTL and models necessary
to cycle-exactly simulate from one to thousands of multi-core compute
nodes, derived directly from silicon-proven and open target-RTL
([RISC-V][riscv] [Rocket Chip][rocket-chip], [BOOM][boom], and [Chipyard][chipyard]), with an optional
cycle-accurate network simulation tying them together. FireSim also
provides a [Linux distribution](https://github.com/firesim/firemarshal)
that is compatible with the RISC-V systems it simulates and
[automates](https://docs.fires.im/en/latest/Advanced-Usage/Workloads/Defining-Custom-Workloads.html)
the process of including new workloads into this Linux distribution.
These simulations run fast
enough to interact with Linux on the simulated system at the command line, [like
a real
computer](https://twitter.com/firesimproject/status/1031267637303508993). Users
can even [SSH into simulated systems in
FireSim](http://docs.fires.im/en/latest/Advanced-Usage/Miscellaneous-Tips.html#experimental-support-for-sshing-into-simulated-nodes-and-accessing-the-internet-from-within-simulations)
and access the Internet from within them.

Head to the [FireSim Website](https://fires.im) to learn more.

## Need help?

* Join the FireSim Mailing list: https://groups.google.com/forum/#!forum/firesim
* Post an issue on this repo
* Follow on Twitter for project updates: [@firesimproject](https://twitter.com/firesimproject)

## Contributing

* See [CONTRIBUTING.md](/CONTRIBUTING.md)

## Publications

### **ISCA 2018**: FireSim: FPGA-Accelerated Cycle-Exact Scale-Out System Simulation in the Public Cloud

You can learn more about FireSim in our ISCA 2018 paper, which covers the overall FireSim infrastructure and large distributed simulations of networked clusters. This paper was **selected as one of IEEE Micro’s “Top Picks from Computer Architecture Conferences, 2018”.**

> Sagar Karandikar, Howard Mao, Donggyu Kim, David Biancolin, Alon Amid, Dayeol
Lee, Nathan Pemberton, Emmanuel Amaro, Colin Schmidt, Aditya Chopra, Qijing
Huang, Kyle Kovacs, Borivoje Nikolic, Randy Katz, Jonathan Bachrach, and Krste
Asanović. **FireSim: FPGA-Accelerated Cycle-Exact Scale-Out System Simulation in
the Public Cloud**. *In proceedings of the 45th International Symposium
on Computer Architecture (ISCA’18)*, Los Angeles, CA, June 2018.

[Paper PDF](https://sagark.org/assets/pubs/firesim-isca2018.pdf) | [IEEE Xplore](https://ieeexplore.ieee.org/document/8416816) | [ACM DL](https://dl.acm.org/citation.cfm?id=3276543) | [BibTeX](https://sagark.org/assets/pubs/firesim-isca2018.bib.txt)


### **FPGA 2019**: FASED: FPGA-Accelerated Simulation and Evaluation of DRAM

Our paper from FPGA 2019 details the DRAM model used in FireSim:

> David Biancolin, Sagar Karandikar, Donggyu Kim, Jack Koenig, Andrew Waterman, Jonathan Bachrach, Krste Asanović, **FASED: FPGA-Accelerated Simulation and Evaluation of DRAM**, *In proceedings of the 27th ACM/SIGDA International Symposium on Field-Programmable Gate Arrays*, Seaside, CA, February 2018.

[Paper PDF](https://people.eecs.berkeley.edu/~biancolin/papers/fased-fpga19.pdf) |
[ACM DL](https://dl.acm.org/citation.cfm?id=3293894) |
[BibTeX](https://people.eecs.berkeley.edu/~biancolin/bib/fased-fpga19.bib)

### **IEEE Micro Top Picks of 2018**: FireSim: FPGA-Accelerated, Cycle-Accurate Scale-Out System Simulation in the Public Cloud

This article discusses several updates since the FireSim ISCA 2018 paper:

> Sagar Karandikar, Howard Mao, Donggyu Kim, David Biancolin, Alon Amid, Dayeol Lee, Nathan Pemberton, Emmanuel Amaro, Colin Schmidt, Aditya Chopra, Qijing Huang, Kyle Kovacs, Borivoje Nikolic, Randy Katz, Jonathan Bachrach, and Krste Asanović. **FireSim: FPGA-Accelerated Cycle-Exact Scale-Out System Simulation in the Public Cloud**. *IEEE Micro, vol. 39, no. 3, pp. 56-65, (Micro Top Picks 2018 Issue)*. May-June 2019.

[Article PDF](https://sagark.org/assets/pubs/firesim-micro-top-picks2018.pdf)

### **ICCAD 2019**: Golden Gate: Bridging The Resource-Efficiency Gap Between ASICs and FPGA Prototypes

Our paper describing FireSim's Compiler, _Golden Gate_:

> Albert Magyar, David T. Biancolin, Jack Koenig, Sanjit Seshia, Jonathan Bachrach, Krste Asanović, **Golden Gate: Bridging The Resource-Efficiency Gap Between ASICs and FPGA Prototypes**, *In proceedings of the 39th International Conference on Computer-Aided Design (ICCAD '19)*, Westminster, CO, November 2019.

[Paper PDF](https://davidbiancolin.github.io/papers/goldengate-iccad19.pdf)

### **ASPLOS 2020**: FirePerf: FPGA-Accelerated Full-System Hardware/Software Performance Profiling and Co-Design

Our paper to appear in ASPLOS 2020 discusses system-level profiling features in FireSim:

> Sagar Karandikar, Albert Ou, Alon Amid, Howard Mao, Randy Katz, Borivoje Nikolić, and Krste Asanović, **FirePerf: FPGA-Accelerated Full-System Hardware/Software Performance Profiling and Co-Design**, *In Proceedings of the Twenty-Fifth International Conference on Architectural Support for Programming Languages and Operating Systems (ASPLOS 2020)*, Lausanne, Switzerland, March 2020.

[Paper PDF](https://sagark.org/assets/pubs/fireperf-asplos2020.pdf)

### **IEEE MICRO 2021**: Accessible, FPGA Resource-Optimized Simulation of Multi-Clock Systems in FireSim

In this special issue, we describe the automated instance-multithreading optimization and support for multiple clock domains in the simulated target.

> David Biancolin, Albert Magyar, Sagar Karandikar, Alon Amid, Borivoje Nikolić, Jonathan  Bachrach, Krste Asanović. **Accessible, FPGA Resource-Optimized Simulation of Multi-Clock Systems in FireSim**. *In IEEE Micro Volume: 41, Issue: 4, July-Aug. 1 2021*

[Article PDF](https://davidbiancolin.github.io/papers/firesim-micro21.pdf)

You can find other publications, including publications that *use* FireSim on the [FireSim Website](https://fires.im/publications/).

[ucb-bar]: http://bar.eecs.berkeley.edu
[eecs]: https://eecs.berkeley.edu
[berkeley]: https://berkeley.edu
[riscv]: https://riscv.org/
[rocket-chip]: https://github.com/freechipsproject/rocket-chip
[boom]: https://github.com/ucb-bar/riscv-boom
[userpubs]: /publications.md#userpapers
[chipyard]: https://github.com/ucb-bar/chipyard
