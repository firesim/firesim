# FireSim: Easy-to-use, Scalable, FPGA-accelerated Cycle-accurate Hardware Simulation

![FireSim Documentation Status](https://readthedocs.org/projects/firesim/badge/)

## Contents

1. [Using FireSim](#using-firesim)
2. [What is FireSim?](#what-is-firesim)
3. [What can I simulate with FireSim?](#what-can-i-simulate-with-firesim)
4. [Need help?](#need-help)
5. [Contributing](#contributing)
6. [Publications](#publications)

## Using FireSim

To get started with using FireSim, see the tutorials on the FireSim documentation
site: https://docs.fires.im/. 

Another good overview (in video format) is our tutorial from the Chisel Community Conference on [YouTube](https://www.youtube.com/watch?v=S3OriQnJXYQ).

## What is FireSim?

FireSim is an [open-source](https://github.com/firesim/firesim) cycle-accurate
FPGA-accelerated full-system hardware simulation platform that runs on cloud FPGAs (Amazon EC2 F1).
FireSim is actively developed in the [Berkeley Architecture Research
Group][ucb-bar] in the [Electrical Engineering and Computer Sciences
Department][eecs] at the [University of California, Berkeley][berkeley].
You can learn more about FireSim in the following places:

* **FireSim website**: https://fires.im
* **FireSim ISCA 2018 Paper**: [Paper PDF](https://sagark.org/assets/pubs/firesim-isca2018.pdf) | [IEEE Xplore](https://ieeexplore.ieee.org/document/8416816) | [ACM DL](https://dl.acm.org/citation.cfm?id=3276543) | [BibTeX](https://sagark.org/assets/pubs/firesim-isca2018.bib.txt) | Selected as one of IEEE Micro’s “Top Picks from Computer Architecture Conferences, 2018”.
* **FireSim documentation**: https://docs.fires.im
* **Two-minute lightning talk from ISCA 2018** (FireSim simulating a datacenter): [YouTube](https://www.youtube.com/watch?v=4XwoSe5c8lY)
* **Chisel Community Conference Tutorial**: [YouTube](https://www.youtube.com/watch?v=S3OriQnJXYQ)
* **Updates/News**: [Changelog](/CHANGELOG.md) | [FireSim Blog](https://fires.im/blog/) | [Twitter](https://twitter.com/firesimproject)

## What can I simulate with FireSim?

FireSim can simulate arbitrary hardware designs written in
[Chisel](https://chisel.eecs.berkeley.edu).  With FireSim, you
can write your own RTL (processors, accelerators, etc.) and run it at
near-FPGA-prototype speeds on cloud FPGAs, while obtaining cycle-accurate
performance results (i.e. matching what you would find if you taped-out
a chip). Depending on the hardware design and the simulation scale,
FireSim simulations run at **10s to 100s of MHz**. You can also integrate
custom software models for components that you don't want/need to write as RTL.

FireSim was originally developed to simulate datacenters by combining
open RTL for RISC-V processors with a custom cycle-accurate network simulation.
By default, FireSim provides all the RTL and models necessary
to **cycle-exactly** simulate from **one to thousands of multi-core compute
nodes**, derived directly from **silicon-proven** and **open** target-RTL
([RISC-V][riscv] [Rocket Chip][rocket-chip] and [BOOM][boom]), with an optional
**cycle-accurate network simulation** tying them together. FireSim also
provides a [Linux distribution](https://github.com/firesim/firesim-software)
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

> Sagar Karandikar, Albert Ou, Alon Amid, Howard Mao, Randy Katz, Borivoje Nikolić, and Krste Asanović, **FirePerf: FPGA-Accelerated Full-System Hardware/Software Performance Profiling and Co-Design**, *To appear, In Proceedings of the Twenty-Fifth International Conference on Architectural Support for Programming Languages and Operating Systems (ASPLOS 2020)*, Lausanne, Switzerland, March 2020.

[Paper PDF](https://sagark.org/assets/pubs/fireperf-asplos2020.pdf)


You can find other publications, including publications that *use* FireSim on the [FireSim Website](https://fires.im/publications/).

[ucb-bar]: http://bar.eecs.berkeley.edu
[eecs]: https://eecs.berkeley.edu
[berkeley]: https://berkeley.edu
[riscv]: https://riscv.org/
[rocket-chip]: https://github.com/freechipsproject/rocket-chip
[boom]: https://github.com/ucb-bar/riscv-boom
