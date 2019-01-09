import sys
import argparse
import math

def main():
    parser = argparse.ArgumentParser(description="Generate CCBench run script")
    parser.add_argument("--wordsize", type=int, default=4)
    parser.add_argument("--linesize", type=int, default=64)
    parser.add_argument("--maxsize",  type=int, default=1 << 24)
    parser.add_argument("--iters",    type=int, default=7108864)
    args = parser.parse_args()

    linewords = args.linesize / args.wordsize
    maxwords  = args.maxsize  / args.wordsize

    startiter = int(math.log(linewords, 2))
    enditer   = int(math.log(maxwords,  2)) + 1

    sizes = [1 << i for i in range(startiter, enditer)]
    types = [0, 1, linewords] # random, unit stride, and cache line stride

    print("#!/bin/bash")
    print("echo \"@NumDataPointsPerSet=[{}]\"".format(len(sizes)))

    for typ in types:
        for size in sizes:
            print("/root/caches {} {} {}".format(size, args.iters, typ))

if __name__ == "__main__":
    main()
