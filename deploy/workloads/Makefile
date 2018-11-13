
# All the tests in here right now are based off the br-disk image.
BASE_IMAGE:=../../sw/firesim-software/images/br-disk.img
BASE_LINUX:=../../sw/firesim-software/images/br-disk-bin

# TODO: ideally we want to restructure this so that:
# Proprietary benchmarks (e.g. spec) are available as separate disks that can
# be attached to your EC2 instance if you have a license
# Regular benchmarks are cloned from git/the internet and built automatically

allpaper: memcached-thread-imbalance simperf-test-latency simperf-test-scale bw-test-two-instances ping-latency

# We use a branch of Speckle (https://github.com/ccelio/Speckle) to cross
# compile the binaries for SPEC2017. These can be compiled locally on a machine
# with the Spec installation, and the overlay directories
# ($SPECKLE_DIR/build/overlay) can be moved EC2

# Default to the submodule
SPECKLE_DIR=Speckle

#TODO: Provide runscripts for fp{speed, rate}
spec17_suites = intrate intspeed
spec17_rootfs_dirs := $(patsubst %, spec17-%, $(spec17-suites))

$(SPECKLE_DIR)/build/overlay/%:
	cd $(SPECKLE_DIR) && ./gen_binaries.sh --compile --suite $*

spec17-%: spec17-%.json $(SPECKLE_DIR)/build/overlay/%
	mkdir -p $@
	cp $(BASE_LINUX) $@/bbl-vmlinux
	python gen-benchmark-rootfs.py -w $< -r -b $(BASE_IMAGE) \
		-s $(SPECKLE_DIR)/build/overlay/$*

memcached-thread-imbalance:
	mkdir -p $@
	sudo yum -y install gengetopt
	sudo pip install matplotlib
	sudo pip install pandas
	cd $@ && git submodule update --init mutilate-loadgen-riscv-release
	cd $@/mutilate-loadgen-riscv-release && ./build.sh
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/mutilate-loadgen-riscv-release/overlay

bw-test-two-instances: bw-test-two-instances.json
	cd ../../sw/network-benchmarks && python build-bw-test.py -n 8
	cp ../../sw/network-benchmarks/testbuild/*.riscv $@

bw-test-one-instance: bw-test-one-instance.json
	cd ../../sw/network-benchmarks && python build-bw-test.py -n 4
	cp ../../sw/network-benchmarks/testbuild/*.riscv $@

ping-latency:
	mkdir -p $@
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

simperf-test:
	mkdir -p $@
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

simperf-test-scale: simperf-test

simperf-test-latency: simperf-test

iperf3: iperf3.json
	mkdir -p $@
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE)

check-rtc:
	cd ../../sw/check-rtc && make check-rtc

check-rtc-linux:
	mkdir -p $@/overlay
	cd ../../sw/check-rtc && make check-rtc-linux
	cp ../../sw/check-rtc/check-rtc-linux $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

checksum-test:
	cd ../../target-design/firechip/tests && make checksum.riscv

.PHONY: $(spec17_overlays) $(spec17_rootfs_dirs) gapbs fedora-uniform \
	memcached-thread-imbalance bw-test-one-instance bw-test-two-instances \
	ping-latency simperf-test simperf-test-latency simperf-test-scale \
	iperf3 check-rtc check-rtc-linux allpaper checksum-test
