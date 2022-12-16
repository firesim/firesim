
import matplotlib
# don't use xwindow
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import pandas as pd
import numpy as np
import re
import sys
import os

basedir = sys.argv[1] + "/"

files = list(map(lambda x: basedir + x, sorted(os.listdir(basedir), key=int)))

def process_uartlog(uartlogpath):
    """ process the log and then report the mean RTT for this link latency """
    def mean(numbers):
	return float(sum(numbers)) / max(len(numbers), 1)

    with open(uartlogpath, 'r') as f:
            readlines = f.readlines()
    rtts = []
    for line in readlines:
        if "64 bytes from 172.16.0.3:" in line:
            thisrtt = line.split()[-2].split("=")[-1]
            rtts.append(float(thisrtt))
    return mean(rtts)


def get_average_rtt_from_file(basedirname):
    uartlogpath = basedirname + "/pinger/uartlog"
    latency = float(basedirname.split("/")[-1])


    latency_in_ms = (latency / 3.2) / 1000000.0

    ideal_rtt = (latency * 4) + 10*2

    ideal_rtt_in_ms = (ideal_rtt / 3.2) / 1000000.0

    measured_rtt_in_ms = process_uartlog(uartlogpath)


    link_latency_us = latency_in_ms * 1000.0
    measured_rtt_in_us = measured_rtt_in_ms * 1000.0
    ideal_rtt_in_us = ideal_rtt_in_ms * 1000.0


    print("DIFF: " + str(measured_rtt_in_us - ideal_rtt_in_us))


    return [link_latency_us, measured_rtt_in_us, ideal_rtt_in_us]

resultarray = list(map(get_average_rtt_from_file, files))

link_latency = list(map(lambda x: x[0], resultarray))
measured_rtt = list(map(lambda x: x[1], resultarray))
ideal_rtt = list(map(lambda x: x[2], resultarray))

print(resultarray)


series = []
fig, ax = plt.subplots()
ser, = plt.plot(link_latency, measured_rtt, linestyle='--', marker='^', c='0.5')
series.append(ser)

ser, = plt.plot(link_latency, ideal_rtt, linestyle='-', marker='o', c='0.1')
series.append(ser)


#matplotlib.rcParams.update({'font.size': 16})
matplotlib.rcParams.update(matplotlib.rcParamsDefault)
ax.legend(series, ['Measured Ping RTT', 'Ideal RTT'],prop={'size': 10})
ax.set_xlabel(r'Link Latency ($\mu$s)', size='10')
ax.set_ylabel(r'Round Trip Time ($\mu$s)', size='10')
ax.grid(linestyle='-', linewidth=0.3)
fig = plt.gcf()
fig.set_size_inches(6, 3.75)
plt.show()
fig.savefig(basedir + 'ping-rtt.pdf', format='pdf')
