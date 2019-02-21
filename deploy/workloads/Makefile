
# All the tests in here right now are based off the br-disk image.
BASE_IMAGE:=../../sw/firesim-software/images/br-disk.img
BASE_LINUX:=../../sw/firesim-software/images/br-disk-bin


# CS152: Put files you'd like to add to the targets file system in cs152/overlay
cs152-overlay-dir:=cs152-overlay

# CS152: This build a rootfs image that will run your script during init
# and tear down the simulator without console output
cs152-automated: cs152-automated.json
	mkdir -p $@
	cp $(BASE_LINUX) $@/bbl-vmlinux
	python gen-benchmark-rootfs.py -w $< -r -b $(BASE_IMAGE) \
		-s $(cs152-overlay-dir)

# CS152: This builds the standard console-driven image, but will mount what ever
# files you've put in your overlay and called out in the cs152-interactive.json
cs152-interactive: cs152-interactive.json
	mkdir -p $@
	cp $(BASE_LINUX) $@/bbl-vmlinux
	python gen-benchmark-rootfs.py -w $< -b $(BASE_IMAGE) \
		-s $(cs152-overlay-dir)

# CS152: This will build an automated image to run CC bench on your target, it'll
# parse uartlog after teardown to generate a plot of cache sizes and latencies.
ccbench-cache-sweep:
	cd ccbench-cache-sweep/ccbench/caches && make ARCH=riscv
	python gen-benchmark-rootfs.py -w $@.json -r -b $(BASE_IMAGE) -s $@/


# CS152: You won't need to use any of the recipes below

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

$(SPECKLE_DIR)/build/overlay/%/$(input): $(SPECKLE_DIR)/riscv.cfg
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
	#cp $(BASE_LINUX) $@/bbl-vmlinux
	#python gen-benchmark-rootfs.py -w $< -r -b $(BASE_IMAGE) \
	#	-s $(GAP_DIR)/overlay/$(input) \



.PHONY: $(spec17_overlays) $(spec17_rootfs_dirs) cs152-automated cs152-interactive gapbs ccbench-cache-sweep
