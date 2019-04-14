import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import pandas as pd
import matplotlib.ticker as mticker
import sys

starterpath = sys.argv[1]
fname = starterpath + '/ccbench-all/uartlog'
outputpath = starterpath + '/outputplot.pdf'

f = open(fname, 'r')
q = f.readlines()
f.close()


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




cacheline_stride_bmark_data = data_from_full_dict(arr_to_dict(cacheline_stride_bmark))
unit_stride_bmark_data = data_from_full_dict(arr_to_dict(unit_stride_bmark))
random_bmark_data = data_from_full_dict(arr_to_dict(random_bmark))

cacheline_ccbench_df = pd.DataFrame(data=cacheline_stride_bmark_data)
unit_ccbench_df = pd.DataFrame(data=unit_stride_bmark_data)
random_ccbench_df = pd.DataFrame(data=random_bmark_data)


cacheline_ccbench_df = cacheline_ccbench_df.sort_values(by=['size'])
unit_ccbench_df = unit_ccbench_df.sort_values(by=['size'])
random_ccbench_df = random_ccbench_df.sort_values(by=['size'])


series = []
cacheline_array_dim = list(cacheline_ccbench_df['size'])
cacheline_array_time = list(cacheline_ccbench_df['time'])

unit_array_dim = list(unit_ccbench_df['size'])
unit_array_time = list(unit_ccbench_df['time'])

random_array_dim = list(random_ccbench_df['size'])
random_array_time = list(random_ccbench_df['time'])



fig, ax = plt.subplots()
ser1, = plt.semilogx(random_array_dim, random_array_time, linestyle='--', marker='*', c='0.1', label='Random Stride')
ser, = plt.semilogx(cacheline_array_dim, cacheline_array_time, linestyle='--', marker='^', c='0.7', label='Cacheline Stride')
ser2, = plt.semilogx(unit_array_dim, unit_array_time, linestyle='--', marker='x', c='0.4', label='Unit Stride')

series.append(ser)
series.append(ser1)
series.append(ser2)

matplotlib.rcParams.update(matplotlib.rcParamsDefault)
ax.set_xlabel(r'Array Dimension', size='12')
ax.set_ylabel(r'Execution Time (cycles)', size='11')
print(cacheline_stride_bmark_data['size'])
ax.xaxis.set_major_formatter(mticker.ScalarFormatter())
ax.xaxis.get_major_formatter().set_scientific(False)
ax.xaxis.get_major_formatter().set_useOffset(False)
plt.minorticks_off()
ax.legend()
ax.set_xticks(cacheline_stride_bmark_data['size'])
ax.grid(linestyle='-', linewidth=0.3)
plt.xticks(fontsize=8, rotation=90)
fig = plt.gcf()
fig.tight_layout()
fig.savefig(outputpath, format='pdf')
