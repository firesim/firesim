

# TODO: ideally we want to restructure this so that:
# Proprietary benchmarks (e.g. spec) are available as separate disks that can
# be attached to your EC2 instance if you have a license
# Regular benchmarks are cloned from git/the internet and built automatically

# build some standard benchmarks. not all of these will work unless you have
# necessary licenses for:
# 	spec 2017
# 	spec 2006

allpaper: memcached-thread-imbalance simperf-test-latency simperf-test-scale bw-test ping-latency

SPEC_RATE_OVERLAY=/benchmarks/overlays/spec17-intrate
spec17-intrate: spec17-intrate.json
	mkdir -p $@
	cp ../../sw/firesim-software/bbl-vmlinux0 $@/bbl-vmlinux
	python gen-benchmark-rootfs.py -w spec17-intrate.json -r -b ../../sw/firesim-software/rootfs0.ext2 -s $(SPEC_RATE_OVERLAY)

SPEC_SPEED_OVERLAY=/benchmarks/overlays/spec17-intspeed
spec17-intspeed: spec17-intspeed.json
	mkdir -p $@
	cp ../../sw/firesim-software/bbl-vmlinux0 $@/bbl-vmlinux
	python gen-benchmark-rootfs.py -w spec17-intspeed.json -r -b ../../sw/firesim-software/rootfs0.ext2 -s $(SPEC_SPEED_OVERLAY)

GAPBS_OVERLAY=/benchmarks/overlays/gapbs
gapbs: gapbs.json
	mkdir -p $@
	cp ../../sw/firesim-software/bbl-vmlinux0 $@/bbl-vmlinux
	python gen-benchmark-rootfs.py -w gapbs.json -r -b ../../sw/firesim-software/rootfs0.ext2 -s $(GAPBS_OVERLAY)

fedora-uniform: fedora-uniform.json
	mkdir -p $@
	#cp ../../sw/firesim-software/bbl-vmlinux0 $@/bbl-vmlinux
	cd $@ && wget https://fedorapeople.org/groups/risc-v/disk-images/stage4-disk.img.xz && unxz -f stage4-disk.img.xz
	cd $@ && wget https://fedorapeople.org/groups/risc-v/disk-images/bbl && mv bbl QEMU-ONLY-bbl
	mkdir -p $@/fedoramount
	sudo mount -t ext4 $@/stage4-disk.img $@/fedoramount
	#sudo cp $@/getty@.service $@/fedoramount/lib/systemd/system/
	#sudo chmod 644 $@/fedoramount/lib/systemd/system/getty@.service
	sudo cp $@/getty@.service $@/fedoramount/usr/lib/systemd/system/
	sudo chmod 644 $@/fedoramount/usr/lib/systemd/system/getty@.service
	sudo ln -s /usr/lib/systemd/system/getty@.service $@/fedoramount/etc/systemd/system/getty.target.wants/getty@hvc0.service
	sudo rm $@/fedoramount/etc/systemd/system/getty.target.wants/getty@tty1.service
	sudo umount $@/fedoramount

memcached-thread-imbalance:
	mkdir -p $@
	sudo yum -y install gengetopt
	sudo pip install matplotlib
	sudo pip install pandas
	cd $@ && git submodule update --init mutilate-loadgen-riscv-release
	cd $@/mutilate-loadgen-riscv-release && ./build.sh
	python gen-benchmark-rootfs.py -w $@.json -r -b ../../sw/firesim-software/rootfs0.ext2 -s $@/mutilate-loadgen-riscv-release/overlay

bw-test: bw-test.json
	cd ../../sw/network-benchmarks && python build.py

ping-latency:
	mkdir -p $@
	python gen-benchmark-rootfs.py -w $@.json -r -b ../../sw/firesim-software/rootfs0.ext2 -s $@/overlay

simperf-test:
	mkdir -p $@
	python gen-benchmark-rootfs.py -w $@.json -r -b ../../sw/firesim-software/rootfs0.ext2 -s $@/overlay

simperf-test-scale: simperf-test

simperf-test-latency: simperf-test

iperf3: iperf3.json
	mkdir -p $@
	cd $@ && ln -sf ../../../sw/firesim-software/bbl-vmlinux0 bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b ../../sw/firesim-software/rootfs0.ext2

check-rtc:
	cd ../../sw/check-rtc && make check-rtc

check-rtc-linux:
	mkdir -p $@/overlay
	cp ../../sw/check-rtc/check-rtc-linux $@/overlay
	cd $@ && ln -sf ../../../sw/firesim-software/bbl-vmlinux0 bbl-vmlinux
	python gen-benchmark-rootfs.py -w $@.json -r -b ../../sw/firesim-software/rootfs0.ext2 -s $@/overlay

.PHONY: spec17-intrate spec17-intspeed gapbs fedora-uniform memcached-thread-imbalance bw-test ping-latency simperf-test simperf-test-latency simperf-test-scale iperf3 check-rtc check-rtc-linux allpaper
