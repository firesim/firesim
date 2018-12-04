import csv
import re
import argparse

from collections import defaultdict

DATA_RE = re.compile(r"^packet timestamp: (\d+), len: (\d+), (sender|receiver): (\d+)$")

def parse_log(f):
    for line in f:
        match = DATA_RE.match(line.strip())
        if match:
            tss, lens, send_receive, node = match.groups()
            yield ((int(tss), int(lens), send_receive == "sender", int(node)))

def gen_result_rows(cycles, send_totals, recv_totals, n, timestep, freq, bitwidth):
    cycles_per_milli = freq * 1e6

    for (i, cycle) in enumerate(cycles):
        datarow = [0 for _ in range(0, 1 + 2 * n)]
        millis = cycle / cycles_per_milli
        datarow[0] = millis

        for node in range(0, n):
            send_total = send_totals[i][node]
            send_bw = (send_total * bitwidth) / (timestep / freq)
            datarow[1 + 2 * node] = send_bw

            recv_total = recv_totals[i][node]
            recv_bw = (recv_total * bitwidth) / (timestep / freq)
            datarow[2 + 2 * node] = recv_bw

        yield datarow

def compute_bw(packet_data, timestep, freq, bitwidth):
    last_node = 0
    last_ts = 0
    send_totals = defaultdict(lambda: defaultdict(int))
    recv_totals = defaultdict(lambda: defaultdict(int))

    for (ts, plen, is_send, node) in packet_data:
        if is_send:
            send_totals[ts // timestep][node] += plen
        else:
            recv_totals[ts // timestep][node] += plen
        last_node = max(last_node, node)
        last_ts = ts

    end_ts = (last_ts // timestep) * timestep
    cycles = range(0, end_ts + 1, timestep)
    n = last_node + 1

    return n, gen_result_rows(
            cycles, send_totals, recv_totals, n, timestep, freq, bitwidth)

def main():
    parser = argparse.ArgumentParser(description="Plot bandwidth over time")
    parser.add_argument("--timestep", dest="timestep", type=int,
                        default = 1000000,
                        help="Time window (in cycles) to measure point bandwidth")
    parser.add_argument("--freq", dest="freq", type=float,
                        default = 3.2, help = "Clock frequency (in GHz)")
    parser.add_argument("--bitwidth", dest="bitwidth", type=int,
                        default = 64, help = "Width of interface (in bits)")
    parser.add_argument("switchlog", help="Switch log to process")
    parser.add_argument("outfile", help="CSV file to output to")
    args = parser.parse_args()

    with open(args.switchlog) as f:
        raw_data = parse_log(f)
        nnodes, result = compute_bw(
            raw_data, args.timestep, args.freq, args.bitwidth)

    with open(args.outfile, "w") as f:
        writer = csv.writer(f)
        titles = ["Time (ms)"]

        for node in range(0, nnodes):
            titles.append("Send BW {}".format(node))
            titles.append("Recv BW {}".format(node))

        writer.writerow(titles)
        for row in result:
            writer.writerow([str(f) for f in row])

if __name__ == "__main__":
    main()
