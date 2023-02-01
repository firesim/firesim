# All the tests in here right now are based off the br-baseimage.
BASE_IMAGE:=../../sw/firesim-software/images/br-base/br-base.img
BASE_LINUX:=../../sw/firesim-software/images/br-base/br-base-bin

# TODO: ideally we want to restructure this so that:
# Proprietary benchmarks (e.g. spec) are available as separate disks that can
# be attached to your EC2 instance if you have a license
# Regular benchmarks are cloned from git/the internet and built automatically

allpaper: memcached-thread-imbalance simperf-test-latency simperf-test-scale bw-test-two-instances ping-latency

spec17-%:
	$(info Support for SPEC2017 has been ported to FireMarshal.)
	$(info See <chipyard_dir>/software/spec2017 and the FireMarshal Docs.)
	$(info https://docs.fires.im/en/latest/Advanced-Usage/Workloads/FireMarshal.html)
	$(info https://firemarshal.readthedocs.io/en/latest/)
	$(info https://chipyard.readthedocs.io/en/latest/Software/index.html)
#Default to test input size for GAPBS
gapbs: input = graph500

$(GAP_DIR)/overlay/$(input):
	cd $(GAP_DIR) && ./gen_run_scripts.sh --binaries --input $(input)


gapbs:  gapbs.json $(GAP_DIR)/overlay/$(input)
	mkdir -p $@
	cp $(BASE_LINUX) $@/bbl-vmlinux
	python3 gen-benchmark-rootfs.py -w $< -r -b $(BASE_IMAGE) \
		-s $(GAP_DIR)/overlay/$(input) \

memcached-thread-imbalance:
	mkdir -p $@
	cd $@ && git submodule update --init mutilate-loadgen-riscv-release
	cd $@/mutilate-loadgen-riscv-release && ./build.sh
	python3 gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/mutilate-loadgen-riscv-release/overlay

bw-test-two-instances: bw-test-two-instances.json
	cd ../../sw/network-benchmarks && python3 build-bw-test.py -n 8
	cp ../../sw/network-benchmarks/testbuild/*.riscv $@

bw-test-one-instance: bw-test-one-instance.json
	cd ../../sw/network-benchmarks && python3 build-bw-test.py -n 4
	cp ../../sw/network-benchmarks/testbuild/*.riscv $@

ping-latency:
	mkdir -p $@
	python3 gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

simperf-test:
	mkdir -p $@
	python3 gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

linux-poweroff:
	mkdir -p $@/overlay
	cd ../../sw/check-rtc && make print-mcycle-linux
	cp ../../sw/check-rtc/print-mcycle-linux $@/overlay/
	python3 gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

simperf-test-scale: simperf-test

simperf-test-latency: simperf-test

flash-stress: simperf-test-latency

iperf3: iperf3.json
	mkdir -p $@
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python3 gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE)

check-rtc:
	cd ../../sw/check-rtc && make check-rtc

check-rtc-linux:
	mkdir -p $@/overlay
	cd ../../sw/check-rtc && make check-rtc-linux
	cp ../../sw/check-rtc/check-rtc-linux $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python3 gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

checksum-test:
	cd ../../target-design/chipyard/tests && make checksum.riscv

ccbench-cache-sweep:
	cd ccbench-cache-sweep/ccbench/caches && make ARCH=riscv
	python3 gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/

fc-test:
	cd ../../sw/network-benchmarks/fc-test && make
	ln -sf ../../../sw/network-benchmarks/fc-test/fc-client.riscv $@/fc-client.riscv
	ln -sf ../../../sw/network-benchmarks/fc-test/fc-server.riscv $@/fc-server.riscv

coremark:
	$(info Support for coremark has been ported to FireMarshal.)
	$(info See <chipyard_dir>/software/coremark and the FireMarshal Docs.)
	$(info https://docs.fires.im/en/latest/Advanced-Usage/Workloads/FireMarshal.html)
	$(info https://firemarshal.readthedocs.io/en/latest/)
	$(info https://chipyard.readthedocs.io/en/latest/Software/index.html)

.PHONY: gapbs fedora-uniform \
	memcached-thread-imbalance bw-test-one-instance bw-test-two-instances \
	ping-latency simperf-test simperf-test-latency simperf-test-scale \
	iperf3 check-rtc check-rtc-linux allpaper checksum-test \
	ccbench-cache-sweep flash-stress fc-test coremark \
	linux-poweroff
