
# All the tests in here right now are based off the br-baseimage.
MARSHAL_DIR := ../../sw/firesim-software
BASE_IMAGE := $(MARSHAL_DIR)/images/br-base/br-base.img
BASE_LINUX := $(MARSHAL_DIR)/images/br-base/br-base-bin

# TODO: ideally we want to restructure this so that:
# Proprietary benchmarks (e.g. spec) are available as separate disks that can
# be attached to your EC2 instance if you have a license
# Regular benchmarks are cloned from git/the internet and built automatically

allpaper: \
	memcached-thread-imbalance \
	simperf-test-latency \
	simperf-test-scale \
	bw-test-two-instances \
	ping-latency

spec17-%:
	$(info Support for SPEC2017 has been ported to FireMarshal.)
	$(info See <chipyard_dir>/software/spec2017 and the FireMarshal Docs.)
	$(info https://docs.fires.im/en/latest/Advanced-Usage/Workloads/FireMarshal.html)
	$(info https://firemarshal.readthedocs.io/en/latest/)
	$(info https://chipyard.readthedocs.io/en/latest/Software/index.html)

fc-test ccbench-cache-sweep check-rtc check-rtc-linux iperf3 simperf-test ping-latency bw-test-one-instance bw-test-two-instances memcached-thread-imbalance:
	marshal -v build marshal-configs/$@.yaml
	#marshal -v install marshal-configs/$@.yaml

linux-poweroff:
	marshal -v build $(MARSHAL_DIR)/example-workloads/linux-poweroff.json
	#marshal -v install $(MARSHAL_DIR)/example-workloads/linux-poweroff.json

simperf-test-scale: simperf-test

simperf-test-latency: simperf-test

flash-stress: simperf-test-latency

coremark:
	$(info Support for coremark has been ported to FireMarshal.)
	$(info See <chipyard_dir>/software/coremark and the FireMarshal Docs.)
	$(info https://docs.fires.im/en/latest/Advanced-Usage/Workloads/FireMarshal.html)
	$(info https://firemarshal.readthedocs.io/en/latest/)
	$(info https://chipyard.readthedocs.io/en/latest/Software/index.html)

.PHONY: \
	allpaper \
	memcached-thread-imbalance bw-test-one-instance bw-test-two-instances \
	ping-latency simperf-test simperf-test-latency simperf-test-scale \
	iperf3 check-rtc check-rtc-linux \
	ccbench-cache-sweep flash-stress fc-test coremark \
	linux-poweroff
