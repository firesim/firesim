import csv
import re
import argparse

from collections import defaultdict

DATA_RE = re.compile(r"^port: (\d+), timediff: (\d+)$")

def parse_log(f):
    for line in f:
        match = DATA_RE.match(line.strip())
        if match:
            port, timediff = match.groups()
            yield (int(port), int(timediff))

def build_histo(packet_data, binsize):
    bins = defaultdict(int)

    for port, timediff in packet_data:
        timebin = int(timediff / binsize)
        bins[(port, timebin)] += 1

    return bins

def main():
    parser = argparse.ArgumentParser(description="Generate latency histogram")
    parser.add_argument("--binsize", dest="binsize", type=int,
                        default = 10, help = "Size of a latency bin in cycles")
    parser.add_argument("switchlog", help="Switch log to process")
    parser.add_argument("outfile", help="CSV file to output to")
    args = parser.parse_args()

    with open(args.switchlog) as f:
        raw_data = parse_log(f)
        bins = build_histo(raw_data, args.binsize)

    with open(args.outfile, "w") as f:
        writer = csv.writer(f)
        titles = ["Port", "Latency", "Count"]

        writer.writerow(titles)

        for ((port, timebin), count) in sorted(bins.items()):
            cycles = (timebin * args.binsize)
            writer.writerow([str(port), str(cycles), str(count)])


if __name__ == "__main__":
    main()
