import sys
import pandas
import numpy as np
import re

workloads_and_benchmarks = {
    "intspeed-1": [
        "600.perlbench_s",
        "602.gcc_s",
        "605.mcf_s",
        "620.omnetpp_s",
        "623.xalancbmk_s",
        "625.x264_s",
        "631.deepsjeng_s",
        "641.leela_s",
        "648.exchange2_s",
        "657.xz_s",
        ## TODO: the first run of xz didn't have split workloads...
        ## need special handling :(
    ],

    "intrate-4": [
        "500.perlbench_r",
        "502.gcc_r",
        "505.mcf_r",
        "520.omnetpp_r",
        "523.xalancbmk_r",
        "525.x264_r",
        "531.deepsjeng_r",
        "541.leela_r",
        "548.exchange2_r",
        "557.xz_r"
    ]
}

# ew:
xz_additional = ["657.xz_s-cld", "657.xz_s-cpu2006docs"]

data_sources = {
    "intspeed-1": [
        ["baseline", "2018-03-19--00-21-41-spec17-intspeed-singlecore-LBP-8W1C-8R1C"],
        ["fcfs", "2018-03-22--22-46-43-spec17-intspeed-1-ddr3-fifo-4R-2133-micro2018-SC-FCFS16GBQuadRank"]
    ],
    "intrate-4": [
        ["baseline", "2018-03-19--01-35-08-spec17-intrate-quadcore-LBP-8W1C-8R1C"],
        ["frfcfs", "2018-03-22--04-58-53-spec17-intrate-4-rate-frfcfs"]
    ]
}

def extract_stats_from_uartlog(uartlogpath):
    """ read a uartlog and get sim perf results """
    elapsed_time = -1
    sim_speed = -1
    cycles = -1
    with open(uartlogpath, 'r') as f:
        readlines = f.readlines()
    for line in readlines:
        if "time elapsed:" in line and "simulation speed" in line:
            elapsed_time = float(line.split()[2])
            sim_speed = float(line.split()[7])
            if line.split()[8] != "MHz":
                # i forget if this will print 0.xxx MHz or switch to KHz
                # so just to be safe...
                print("ERR: unknown sim rate units")
                exit(1)
        if "*** PASSED ***" in line:
            cycles = float(line.split()[4])
    return [elapsed_time, sim_speed, cycles]


output_data = dict()
basedir = sys.argv[1]

def get_uartlogpath(basedir, rowfilesdir, bmark):
    return """{}/{}/{}/uartlog""".format(basedir, rowfilesdir, bmark)

for workload in data_sources.keys():
    ### workload is a complete workload, like intspeed-1 or intrate-4
    data_sources_for_workload = data_sources[workload]
    benchmarks = workloads_and_benchmarks[workload]

    for run in data_sources_for_workload:
        ## run is a "row" in the table, consists of a name and directory
        rowname = run[0]
        rowfilesdir = run[1]
        datarow = []
        for bmark in benchmarks:
            uartloglocation = get_uartlogpath(basedir, rowfilesdir, bmark)
            try:
                dataresult = extract_stats_from_uartlog(uartloglocation)
            except:
                if bmark == "657.xz_s":
                    # get results from xz_additional instead, sum them
                    uartloglocation = get_uartlogpath(basedir, rowfilesdir, xz_additional[0])
                    dataresult0 = extract_stats_from_uartlog(uartloglocation)
                    uartloglocation = get_uartlogpath(basedir, rowfilesdir, xz_additional[1])
                    dataresult1 = extract_stats_from_uartlog(uartloglocation)
                    # compute result manually
                    elapsed_time = dataresult0[0] + dataresult1[0]
                    cycles = dataresult0[2] + dataresult1[2]
                    # report sim speed in MHz:
                    sim_speed = (cycles / elapsed_time) / 1000000.0
                    dataresult = [elapsed_time, sim_speed, cycles]
                else:
                    # something has gone terribly wrong
                    print("WAT")
                    exit(1)
            datarow.append(dataresult)
        output_data[workload + "-" + rowname] = datarow

for key in output_data.keys():
    existingdat = output_data[key]
    newdat = []
    for dat in existingdat:
        # originally,
        # 0 is runtime in s
        # 1 is sim speed mhz
        newdat.append(round(dat[0] / 3600.0, 1)) # convert to hours
        newdat.append(round(dat[1], 1))
    output_data[key] = np.array(newdat)


# Prune off the number and _r/_s suffix
def get_shortnames(long_names):
    short_name_regex = re.compile("\d*\.(\w*)_")
    short_names = []
    for name in long_names:
        m = re.match(short_name_regex, name)
        if m is not None:
            short_names += [m.group(1)]

    return short_names


iterables = [get_shortnames(workloads_and_benchmarks["intspeed-1"]), ["time", "freq"]]

newindex = pandas.MultiIndex.from_product(iterables, names=['benchmark', ''])


print("""\\begin{table*}[t]\\""")

pframe = pandas.DataFrame(data=[output_data["intspeed-1-baseline"], output_data["intspeed-1-FCFS-256K-LLC"]], columns=newindex, index = ["baseline", "fcfs"])

print(pframe.to_latex())

print("""\\caption{intspeed-1 results}""")

print("""\\end{table*}""")


iterables = [get_shortnames(workloads_and_benchmarks["intrate-4"]), ["time", "freq"]]

newindex = pandas.MultiIndex.from_product(iterables, names=['benchmark', ''])


print("""\\begin{table*}[t]""")

pframe = pandas.DataFrame(data=[output_data["intrate-4-baseline"], output_data["intrate-4-frfcfs"]], columns=newindex, index = ["baseline", "frfcfs"])

print(pframe.to_latex())

print("""\\caption{intrate-4 results}""")

print("""\\end{table*}""")

