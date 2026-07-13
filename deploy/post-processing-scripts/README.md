A collection of scripts derived from Chris Celio's plotting scripts to
parse and analyze data spat out by MIDAS-generated simulators.

## Firesim Result Files 

In general, a firesim runs spits out a directory in deploy/results-workload/<run-date-and-name>, with subdirectories for each simulator instance's output. 
```
<run-date-and-name>
  <instance-job-name =~ benchark-name>/
    hpm_data/
      hpm_counters0.out // Dumps to standard out from the hpm_counters program
      ...
      hpm_counters<harts-1>.out
    output/  // application stdout and stderr 
      *.out 
      *.err
    memory_stats.csv // The values of all programmable registers in the memory model (sampled every 100M cycles) 32 bit values.
    uartlog // Driver stdout + UART. Has simulation frequency, host wallclock time.
```

## Script: data_parser.py
Converts the hpm_counters<hart>.out file to a csv file in the same format as memory_stats.csv. These generated files will live in:
```<benchmark-dir>/*/hpm_counters.csv```
  
## Script: plot.py
Parses csv files and generates time-series plots for various metrics. Buggy AF.

## Script: summarize.py
Takes a set of benchmark dirs (expected to be different runs of the same benchmark) and spits out values for metrics over the entire run of each application. A starting point for producing barplots of a metric across multiple experiments.


