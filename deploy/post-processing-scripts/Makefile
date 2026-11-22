
CONFIG_DIR=configs
FIGURE_DIR=figures
HISTOGRAM_INPUT=data/intrate-histograms/502.gcc_r/latency_histogram.csv
HISTOGRAM_OUTPUT=figures/histogram_gcc_r.pdf

all: runtime fidelity histogram

runtime:
	./runtime_bar_plot.py --config $(CONFIG_DIR)/runtime_bar_config.json

fidelity:
	./dram_fidelity_runtime_plot.py --config $(CONFIG_DIR)/dram_fidelity_config.json

histogram:
	./histogram_plot.py $(CONFIG_DIR)/histogram_config.json $(HISTOGRAM_INPUT) $(HISTOGRAM_OUTPUT)

fetch_data:
	mkdir -p data
	aws s3 sync s3://micro-2018-mem-model-results/results-workload data --exclude "*" --include "*latency_histogram.csv" --include "*post_processed*"

clean:
	rm -rf $(FIGURE_DIR)/*

.PHONY: clean fetch_data all runtime fidelity histogram
