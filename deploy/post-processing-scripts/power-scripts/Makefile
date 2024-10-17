
# Ranks and IDX must be in sync
RANKS=4
IDX = 0 1 2 3
CYCLES = 1000000000
RAW_TRACE = ../data/powerdat2.csv
DRAM_CONFIG = MIDAS-MICRON_2Gb_DDR3-2133_8bit.xml
DRAMPower = ~/DRAMPower-4.0/drampower
POWER_CSV = power.csv
RANK_TRACES = $(addsuffix .trace.csv,$(addprefix rank,$(IDX)))
POWER_CSVS = $(addsuffix .power.csv,$(addprefix rank,$(IDX)))

big_data: fast_translate_cmd_trace
	mkfifo $(RANK_TRACES)
	./fast_translate_cmd_trace 4 $(RAW_TRACE) rank . &
	./calculate_power.py $(DRAMPower) $(DRAM_CONFIG) rank0.trace.csv rank0.power.csv $(CYCLES) &
	./calculate_power.py $(DRAMPower) $(DRAM_CONFIG) rank1.trace.csv rank1.power.csv $(CYCLES) &
	./calculate_power.py $(DRAMPower) $(DRAM_CONFIG) rank2.trace.csv rank2.power.csv $(CYCLES) &
	./calculate_power.py $(DRAMPower) $(DRAM_CONFIG) rank3.trace.csv rank3.power.csv $(CYCLES)
	rm -rf $(RANK_TRACES)

fast_translate_cmd_trace: fast_translate_cmd_trace.c
	gcc -std=c99 -O2 $^ -o $@

clean:
	rm -rf $(RANK_TRACES) tmp* fast_translate_cmd_trace

.PHONY: big_data clean

