#!/usr/bin/python
import csv
import os
import re
import numpy as np
import fnmatch
import matplotlib
import glob
from functools import partial
matplotlib.use('PDF') # must be called immediately, and before import pylab
import matplotlib.pyplot as plt

REPORT_DIR = 'QoR/'
REPORT_NAME_SUFFIX = '*post_route_utilization.rpt'
TIMING_REPORT_NAME_SUFFIX = '*post_route_timing.rpt'
# Names of the instances within the hiearchy to collect utilization from
INSTANCE_NAMES = [ 'MemoryModel' ] 


PERIOD = float(2.5)

design_points = [REPORT_DIR + point for point in  os.listdir(REPORT_DIR)]

print design_points

data = {}
labels = None

for point in design_points:
    rpt_name = glob.glob(point + '/' + REPORT_NAME_SUFFIX)[0]

    with open(rpt_name, 'rb') as rpt_file:
        data[point] = {}
        print point
        for line in  rpt_file.readlines():
            if  re.match('\|\s*Instance\s+', line) is not None and labels is None:
                labels = [x for x, y in re.findall("(\w+( \w+)?)", line)]
                print labels
            for name in INSTANCE_NAMES:
                m = re.match('\|\s*' + name + '\s+', line)
                if m is not None:
                    columns = [x for x in re.findall("\|\s+(\S+)\s+", line)]
                    print len(columns)
                    data[point][name] = {}
                    for x, y in zip(labels, columns):
                        data[point][name][x] = y


timing = {}
for point in design_points:
    rpt_name = glob.glob(point + '/' + TIMING_REPORT_NAME_SUFFIX)[0]
    with open(rpt_name, 'rb') as timing_rpt_file:
        for line in timing_rpt_file.readlines():
            m = re.match('\s*Slack\s\(VIOLATED\)\s*:\s*\-([0-9.]*)', line)
            if m is not None:
                neg_slack = m.groups()[0]
                timing[point] = 1000/(PERIOD + float(neg_slack))



print timing
fig = plt.plot()
fpga_resources = {
        'Logic LUTs': 914400,
        'FFs': 1828800,
        'Memory LUTs': 460320,
        'BRAMs': 1680, #36Kb
        'DSPs': 5640}


#prim_types = ['LUTs', 'FFs', 'BRAM', 'DSP']

boom_points = list(filter(lambda (x,y): 'boom' in x, data.iteritems()))
rocket_points = list(filter(lambda (x,y): 'rocket' in x, data.iteritems()))
fame_points = list(filter(lambda (x,y): 'fame1' in x, data.iteritems()))
all_points = list(data.iteritems())

def get_BRAM(points):
    return (int(points['RAMB18']) + 2*int(points['RAMB36']) + 1)/2

def get_LUTL(points):
    return int(points['Logic LUTs'])

def get_reg(points):
    return int(points['FFs'])

def get_LUTM(points):
    return (int(points['LUTRAMs']) + int(points['SRLs']))

def get_LUTL_utilization(points):
    return 100*(float(points['Logic LUTs'])/float(fpga_resources['Logic LUTs']))

def get_reg_utilization(points):
    return 100*(float(points['FFs'])/float(fpga_resources['FFs']))
def get_BRAM_utilization(points):
    return 100*((float(points['RAMB18']) + 2*float(points['RAMB36']))/(float(fpga_resources['BRAMs'])*2))

def get_LUTL_utilization(points):
    return 100*(float(points['Logic LUTs'])/float(fpga_resources['Logic LUTs']))

def get_reg_utilization(points):
    return 100*(float(points['FFs'])/float(fpga_resources['FFs']))
def get_LUTM_utilization(points):
    return 100*((float(points['LUTRAMs']) + float(points['SRLs']))/float(fpga_resources['Memory LUTs']))

def get_DSP_utilization(points):
    return 100*(float(points['DSP48 Blocks'])/fpga_resources['DSPs'])


prim_types = [
        ("Logic LUTs", get_LUTL_utilization),
        ("Registers", get_reg_utilization),
        ("BRAMs", get_BRAM_utilization),
        ("Memory LUTs", get_LUTM_utilization),
        ("DSP48s", get_DSP_utilization)]

prim_types_abs = [
        ("Logic LUTs", get_LUTL),
        ("Registers", get_reg),
        ("BRAMs", get_BRAM),
        ("Memory LUTs", get_LUTM)
        ]
# Plot tuning constants
bar_spacing = 2 * 0.0625
bar_width = 2 * 0.05
plot_height = 3
plot_width = 6


def get_blue_color_list(num_points):
    return plt.cm.seismic(np.linspace(0.5, 0, num_points)).tolist()

def get_red_color_list(num_points):
    return plt.cm.seismic(np.linspace(0.5, 1, num_points)).tolist()

def tight_layout():
    plt.tight_layout(pad=0.5, w_pad=0.5, h_pad=0.5)

# Plot generation functions

def gen_relative_utilizations(plot_name, design_node, points, color_list):
    prim_types = ['Total LUTs', 'Logic LUTs', 'FFs', 'RAMB18', 'RAMB36', 'LUTRAMs']
    index = np.arange(len(prim_types))
    num_points = len(points)
    sorted_points = sorted(points, key = lambda (k,v): int(v[design_node]['Total LUTs']))

    for (name, point) in sorted_points:
        print name
        for tpe in prim_types:
            print tpe + ": " + point[design_node][tpe]
            

    for i, (point, values) in enumerate(sorted_points):

        points = []
        for prim in prim_types:
            print point
            baseline = sorted_points[0][1][design_node][prim]
            points.append(float(values[design_node][prim])/float(baseline))
        label_name = re.findall('-(\w*)$',point)[0]
        rects = plt.bar(index + (i+1)*(bar_spacing) - bar_width, points, bar_width, label = label_name, color = color_list[i])
    plt.xlabel('FPGA Resource')
    plt.ylabel('Relative Utilization')
    plt.xticks(index + num_points*(bar_spacing)/2 + 0.025, prim_types)
    #plt.legend()

    tight_layout()
    plt.savefig('figures/' + plot_name + '.pdf')
    plt.clf()

def gen_total_utilizations(plot_name, design_node, points, color_list):

    index = np.arange(len(prim_types))
    num_points = len(points)
    sorted_points = sorted(points, key = lambda (k,v): int(v[design_node]['Total LUTs']))

    plt.figure(figsize=(plot_width, plot_height))
    #color_list = get_red_color_list(num_points/2) + (get_blue_color_list(num_points/2))

    for i, (point, values) in enumerate(sorted_points):

        points = []
        for (name, prim) in prim_types:
            print point
            points.append(prim(values[design_node]))
        
        short_name = re.findall('/(.*)$',point)
        if short_name:
            label_name = short_name[0]
        else:
            label_name = 'lol'

        rects = plt.bar(index + (i+1)*(bar_spacing) - bar_width, points, bar_width, label = label_name, color = color_list[i])
    plt.xlabel('FPGA Resource')
    plt.ylabel('Total Utilization (%)')
    plt.xticks(index + num_points*(bar_spacing)/2 + (bar_spacing - bar_width)/2, [k for (k, v) in prim_types])
    #plt.legend(fontsize = 14)

    tight_layout()
    plt.savefig('figures/' + plot_name + '.pdf')
    plt.clf()

def gen_latex_table(design_node, points):

    index = np.arange(len(prim_types))
    num_points = len(points)
    sorted_points = points

    table_row = "Instance"
    for (name, prim) in prim_types_abs:
        table_row += " & " + name

    print timing
    print table_row + " &  fMAX \\\\"
    for i, (point, values) in enumerate(sorted_points):
        table_row = point
        for (name, prim) in prim_types_abs:
            table_row += " & " + str(prim(values[design_node]))

    
        print table_row + "& {:3.0f} \\\\".format(timing[point]) 
#gen_relative_utilizations("boom-topsp-relutil", 'top_sp', boom_points)
#gen_relative_utilizations("rocket-topsp-relutil", 'top_sp', rocket_points)
#gen_total_utilizations("boom-topsp-totalutil", 'top_sp', boom_points, get_blue_color_list(len(boom_points)))
#gen_total_utilizations("rocket-topsp-totalutil", 'top_sp', rocket_points, get_red_color_list(len(rocket_points)))
#gen_latex_table('top_sp', rocket_points)
#gen_latex_table('top_sp', boom_points)
#gen_total_utilizations("rocket-boom-topsp-totalutil", 'firesim_top', fame_points, get_red_color_list(2))
#gen_relative_utilizations("rocket-boom-firesim_top-relutil", 'firesim_top', fame_points, get_red_color_list(2))

#gen_total_utilizations("isca-mem-totalutil", 'MemoryModel', all_points, get_red_color_list(10))
gen_latex_table("MemoryModel", all_points)
