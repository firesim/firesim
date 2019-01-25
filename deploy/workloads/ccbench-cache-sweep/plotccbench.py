import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
#from mpl_toolkits.mplot3d import Axes3D
import pandas as pd
#import numpy as np
#import re
#import sys
#from collections import OrderedDict
import matplotlib.ticker as mticker

fname = '/home/centos/firesim/deploy/results-workload/2019-01-25--07-44-19-ccbench-cache-sweep/ccbench-all/output/RESULTSFILE'

f = open(fname, 'r')
q = f.readlines()
f.close()
#print(q)


q = filter(lambda x: x.startswith('App:'), q)
q = map(lambda x: x.strip().split(","), q)
q = map(lambda x: list(map(lambda z: z.split(":"), x)), q)


def arr_to_dict(q):
    # to dicts
    as_dict = []
    for elem in q:
        d = dict()
        for pair in elem:
            d[pair[0]] = pair[1]
        as_dict.append(d)
    return as_dict

cacheline_stride_bmark = filter(lambda x: ['RunType', '[16]'] in x, q)
unit_stride_bmark = filter(lambda x: ['RunType', '[1]'] in x, q)
random_bmark = filter(lambda x: ['RunType', '[0]'] in x, q)

def data_from_full_dict(array_of_dict):
    times = []
    sizes = []
    for d in array_of_dict:
        time = eval(d['Time'])[0]
        appsize = eval(d['AppSize'])[0] * 4
        times.append(time)
        sizes.append(appsize)
    return {'size': sizes, 'time': times}

cacheline_stride_bmark_data = data_from_full_dict(arr_to_dict(random_bmark))

ccbench_df = pd.DataFrame(data=cacheline_stride_bmark_data)
print(ccbench_df)

#ccbench_df = pd.read_csv('ccbench.csv')
#ccbench_df['size'] = ccbench_df['size'].astype(int)
ccbench_df = ccbench_df.sort_values(by=['size'])
print(ccbench_df)


series = []
array_dim = list(ccbench_df['size'])

array_time = list(ccbench_df['time'])

fig, ax = plt.subplots()
ser, = plt.semilogx(array_dim, array_time, linestyle='--', marker='^', c='0.7')
series.append(ser)


#matplotlib.rcParams.update({'font.size': 16})
matplotlib.rcParams.update(matplotlib.rcParamsDefault)
ax.set_xlabel(r'Array Dimension', size='12')
ax.set_ylabel(r'Execution Time (cycles)', size='11')
#ax.set_xscale('log', basex=2)
print(cacheline_stride_bmark_data['size'])
#plt.ticklabel_format(useOffset=False)
ax.xaxis.set_major_formatter(mticker.ScalarFormatter())
ax.xaxis.get_major_formatter().set_scientific(False)
ax.xaxis.get_major_formatter().set_useOffset(False)
plt.minorticks_off()

ax.set_xticks(cacheline_stride_bmark_data['size'])
ax.grid(linestyle='-', linewidth=0.3)
plt.xticks(fontsize=8, rotation=90)
fig = plt.gcf()
fig.tight_layout()
fig.savefig('yolo.pdf', format='pdf')
#plt.show() 


