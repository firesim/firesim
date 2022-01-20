#!/usr/bin/env python3

import argparse
import random
from itertools import izip, count

parser = argparse.ArgumentParser(description="Generate a hex file to intialize the host memory when using the pointer chaser application.")

parser.add_argument("--base", dest = "base_address", default = 64, help = "The base address of the linked list")

parser.add_argument("--length", dest = "list_length", default = 48, help = "The length of the singly linked list")

parser.add_argument("--output_file", dest = "output_file", default = "init.hex", help = "The name of the hex file to be generated")


args = parser.parse_args()
addresses = random.sample(range(args.base_address + 64, 1024*1024, 64),
            args.list_length)

nodes = []
for i, current, next_node in izip(count(),[args.base_address] + addresses[:], addresses[:] + [0]):
    nodes.append((current,i,next_node))


f = open(args.output_file, "w")
addr = 0;
for node in sorted(nodes):
    while (addr < node[0]):
        f.write("{:032x}\n".format(0))
        addr += 16
    f.write("{:016x}{:016x}\n".format(node[1], node[2]))
    addr += 16

f.close()
