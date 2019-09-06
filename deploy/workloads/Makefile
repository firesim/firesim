
# All the tests in here right now are based off the br-baseimage.
BASE_IMAGE:=../../sw/firesim-software/images/br-base.img
BASE_LINUX:=../../sw/firesim-software/images/br-base-bin

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
GAP_DIR=runscripts/gapbs-scripts

#TODO: Provide runscripts for fp{speed, rate}
spec17_suites = intrate intspeed
spec17_rootfs_dirs := $(patsubst %, spec17-%, $(spec17-suites))

#Default to ref input size for SPEC17
spec17-%: input = ref

$(SPECKLE_DIR)/build/overlay/%/$(input):
	cd $(SPECKLE_DIR) && ./gen_binaries.sh --compile --suite $* --input $(input)

spec17-%: spec17-%.json $(SPECKLE_DIR)/build/overlay/%/$(input)
	mkdir -p $@
	cp $(BASE_LINUX) $@/bbl-vmlinux
	python gen-benchmark-rootfs.py -w $< -r -b $(BASE_IMAGE) \
		-s $(SPECKLE_DIR)/build/overlay/$*/$(input)

#Default to test input size for GAPBS
gapbs: input = graph500

$(GAP_DIR)/overlay/$(input):
	cd $(GAP_DIR) && ./gen_run_scripts.sh --binaries --input $(input)
 

gapbs:  gapbs.json $(GAP_DIR)/overlay/$(input) 
	mkdir -p $@
	cp $(BASE_LINUX) $@/bbl-vmlinux
	python gen-benchmark-rootfs.py -w $< -r -b $(BASE_IMAGE) \
		-s $(GAP_DIR)/overlay/$(input) \

memcached-thread-imbalance:
	mkdir -p $@
	sudo yum -y install gengetopt
	sudo pip2 install matplotlib
	sudo pip2 install pandas
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

linux-immediate-poweroff:
	mkdir -p $@
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE)

simperf-test-scale: simperf-test

simperf-test-latency: simperf-test

flash-stress: simperf-test-latency

iperf3: iperf3.json
	mkdir -p $@
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

iperf3-loopback: iperf3-loopback.json
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

check-rtc:
	cd ../../sw/check-rtc && make check-rtc

check-rtc-linux:
	mkdir -p $@/overlay
	cd ../../sw/check-rtc && make check-rtc-linux
	cp ../../sw/check-rtc/check-rtc-linux $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

checksum-test:
	cd ../../target-design/chipyard/tests && make checksum.riscv

memblade-test:
	cd ../../sw/memblade-tests/baremetal && make memblade-test.riscv memblade-dummy.riscv

memblade-page-bmark:
	cd ../../sw/memblade-tests/baremetal && make memblade-page-bmark.riscv memblade-dummy.riscv

memblade-linux-bmark:
	cd ../../sw/memblade-tests/linux && make memblade-bmark.riscv
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cp ../../sw/memblade-tests/linux/memblade-bmark.riscv $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

memblade-linux-bmark2:
	cd ../../sw/memblade-tests/linux && make memblade-bmark.riscv
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cp ../../sw/memblade-tests/linux/memblade-bmark.riscv $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

memblade-lat:
	cd ../../sw/memblade-tests/linux && make memblade-lat.riscv
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cp ../../sw/memblade-tests/linux/memblade-lat.riscv $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

memblade-lat2:
	cd ../../sw/memblade-tests/linux && make memblade-{lat,bmark}.riscv
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cp ../../sw/memblade-tests/linux/memblade-{lat,bmark}.riscv $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

pagerank-local:
	mkdir -p $@/overlay
	cd ../../sw/memblade-tests/linux && make pagerank-local.riscv
	cp ../../sw/memblade-tests/linux/pagerank-local.riscv $@/overlay
	cp ../../sw/memblade-tests/data/* $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

pagerank-local2:
	mkdir -p $@/overlay
	cd ../../sw/memblade-tests/linux && make pagerank-local.riscv
	cp ../../sw/memblade-tests/linux/pagerank-local.riscv $@/overlay
	cp ../../sw/memblade-tests/data/* $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

pagerank-remote:
	mkdir -p $@/overlay
	cd ../../sw/memblade-tests/linux && make pagerank-remote.riscv load-memblade.riscv
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cp ../../sw/memblade-tests/linux/{pagerank-remote,load-memblade}.riscv $@/overlay
	cp ../../sw/memblade-tests/data/* $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

pagerank-remote2:
	mkdir -p $@/overlay
	cd ../../sw/memblade-tests/linux && make pagerank-remote.riscv load-memblade.riscv
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cp ../../sw/memblade-tests/linux/{pagerank-remote,load-memblade}.riscv $@/overlay
	cp ../../sw/memblade-tests/data/* $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

pagerank-dram-cache:
	mkdir -p $@/overlay
	cd ../../sw/memblade-tests/linux && make pagerank-dram-cache.riscv
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cp ../../sw/memblade-tests/linux/pagerank-dram-cache.riscv $@/overlay
	cp ../../sw/memblade-tests/data/* $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

histo-local:
	mkdir -p $@/overlay
	cd ../../sw/memblade-tests/linux && make histo-local.riscv
	cp ../../sw/memblade-tests/linux/histo-local.riscv $@/overlay
	cp ../../sw/memblade-tests/data/histo-data-26-22.bin $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

fc-test:
	cd ../../sw/network-benchmarks/fc-test && make
	ln -sf ../../../sw/network-benchmarks/fc-test/fc-client.riscv $@/fc-client.riscv
	ln -sf ../../../sw/network-benchmarks/fc-test/fc-server.riscv $@/fc-server.riscv

ccbench:
	mkdir -p $@/overlay
	cd ../../sw/ccbench/caches && make ARCH=riscv clean caches
	cp ../../sw/ccbench/caches/caches $@/overlay
	python $@/gen-run-script.py > $@/overlay/run-test.sh
	chmod +x $@/overlay/run-test.sh
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

ccbench-dram-cache:
	mkdir -p $@/overlay
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cd ../../sw/ccbench/dc_caches && make ARCH=riscv clean caches
	cp ../../sw/ccbench/dc_caches/caches $@/overlay
	python $@/gen-run-script.py > $@/overlay/run-test.sh
	chmod +x $@/overlay/run-test.sh
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

dram-cache-test-linux:
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cd ../../sw/memblade-tests/linux && make dram-cache-test.riscv
	cp ../../sw/memblade-tests/linux/dram-cache-test.riscv $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

saxpy:
	cd ../../sw/memblade-tests/linux && make saxpy.riscv
	cp ../../sw/memblade-tests/linux/saxpy.riscv $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

saxpy-dc:
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cd ../../sw/memblade-tests/linux && make saxpy-dc.riscv
	cp ../../sw/memblade-tests/linux/saxpy-dc.riscv $@/overlay
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

ccbench-cache-sweep:
	cd ccbench-cache-sweep/ccbench/caches && make ARCH=riscv
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/

rmem-alloc-test:
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cd ../../sw/memblade-tests/rmem-alloc && make
	cp ../../sw/memblade-tests/rmem-alloc/*.riscv $@/overlay
	cd $@ && ln -sf ../../../sw/memblade-tests/baremetal/memblade-dummy.riscv
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

flush-test:
	cd ../../sw/memblade-tests/linux && make flush-test-{master,slave}.riscv
	cp ../../sw/memblade-tests/linux/flush-test-{master,slave}.riscv $@/overlay
	cd ../../sw/memblade-tests/baremetal && make memblade-dummy.riscv
	cd $@ && ln -sf ../../../sw/memblade-tests/baremetal/memblade-dummy.riscv
	cd $@ && ln -sf ../$(BASE_LINUX) bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

coremark:
	cd coremark/riscv-coremark/coremark && make CC=riscv64-unknown-linux-gnu-gcc compile
	mv coremark/riscv-coremark/coremark/coremark.exe coremark/overlay/coremark.riscv
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/overlay

.PHONY: $(spec17_overlays) $(spec17_rootfs_dirs) gapbs fedora-uniform \
	memcached-thread-imbalance bw-test-one-instance bw-test-two-instances \
	ping-latency simperf-test simperf-test-latency simperf-test-scale \
	iperf3 iperf3-loopback check-rtc check-rtc-linux \
	allpaper checksum-test memblade-test memblade-page-bmark \
	memblade-linux-bmark memblade-linux-bmark2 memblade-lat memblade-lat2 \
	pagerank-local pagerank-local2 pagerank-remote pagerank-remote2 \
	histo-local fc-test ccbench ccbench-dram-cache dram-cache-test-linux \
	saxpy saxpy-dc pagerank-dram-cache ccbench-cache-sweep flash-stress \
	rmem-alloc-test flush-test coremark linux-immediate-poweroff
