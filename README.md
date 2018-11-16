# FireSim: Easy-to-use, Scalable, FPGA-accelerated Cycle-accurate Hardware Simulation

![FireSim Documentation Status](https://readthedocs.org/projects/firesim/badge/)

## Using FireSim

To get started with using FireSim, see the tutorials on the FireSim documentation
site: https://docs.fires.im/

## What is FireSim?

FireSim is an [open-source](https://github.com/firesim/firesim) cycle-accurate,
FPGA-accelerated scale-out computer system simulation platform developed in the
[Berkeley Architecture Research Group][ucb-bar] in the [Electrical Engineering
and Computer Sciences Department][eecs] at the [University of California,
Berkeley][berkeley].

FireSim is capable of simulating from **one to thousands of multi-core compute
nodes**, derived directly from **silicon-proven** and **open** target-RTL (e.g. [RISC-V][riscv] [Rocket Chip][rocket-chip] and [BOOM][boom]), with
an optional *cycle-accurate network simulation* tying them together. FireSim
runs on FPGAs in **public cloud** environments like [AWS EC2
F1](https://aws.amazon.com/ec2/instance-types/f1/), removing the high capex
traditionally involved in large-scale FPGA-based simulation. FireSim also 
provides a [Linux distribution](https://github.com/firesim/firesim-software)
that is compatible with the systems it simulates and
[automates](https://docs.fires.im/en/latest/Advanced-Usage/Workloads/Defining-Custom-Workloads.html)
the process of including new workloads into this Linux distribution.

You can learn more about FireSim in the following places:

* **FireSim website**: https://fires.im
* **FireSim ISCA 2018 Paper**: [Paper PDF](https://sagark.org/assets/pubs/firesim-isca2018.pdf) | [IEEE Xplore](https://ieeexplore.ieee.org/document/8416816) | [ACM DL](https://dl.acm.org/citation.cfm?id=3276543)
* **FireSim documentation**: https://docs.fires.im
* **Two-minute lightning talk from ISCA 2018** (FireSim simulating a datacenter): [YouTube](https://www.youtube.com/watch?v=4XwoSe5c8lY)

## Need help?

* Join the FireSim Mailing list: https://groups.google.com/forum/#!forum/firesim
* Post an issue on this repo
* Follow on Twitter for project updates: [@firesimproject](https://twitter.com/firesimproject)

## Contributing

* See CONTRIBUTING.md

## ISCA 2018 Paper

You can learn more about FireSim in our ISCA 2018 paper, which focuses on
FireSim simulations with a globally-cycle-accurate network simulation:

Sagar Karandikar, Howard Mao, Donggyu Kim, David Biancolin, Alon Amid, Dayeol
Lee, Nathan Pemberton, Emmanuel Amaro, Colin Schmidt, Aditya Chopra, Qijing
Huang, Kyle Kovacs, Borivoje Nikolic, Randy Katz, Jonathan Bachrach, and Krste
Asanović. **FireSim: FPGA-Accelerated Cycle-Exact Scale-Out System Simulation in
the Public Cloud**. *In proceedings of the 45th International Symposium
on Computer Architecture (ISCA’18)*, Los Angeles, CA, June 2018.

[Paper PDF](https://sagark.org/assets/pubs/firesim-isca2018.pdf) | [IEEE Xplore](https://ieeexplore.ieee.org/document/8416816) | [ACM DL](https://dl.acm.org/citation.cfm?id=3276543)


[ucb-bar]: http://bar.eecs.berkeley.edu
[eecs]: https://eecs.berkeley.edu
[berkeley]: https://berkeley.edu
[riscv]: https://riscv.org/
[rocket-chip]: https://github.com/freechipsproject/rocket-chip
[boom]: https://github.com/ucb-bar/riscv-boom
