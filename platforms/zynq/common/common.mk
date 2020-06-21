# This makefrag is sourced by each board's subdirectory

JOBS = 16
base_dir = $(abspath ../..)
common = $(base_dir)/common
output_delivery = deliver_output
SHELL := /bin/bash

ifneq ($(BOARD_MODEL),)
	insert_board = s/\# REPLACE FOR OFFICIAL BOARD NAME/set_property "board_part" "$(BOARD_MODEL)"/g
endif

proj_name = project

verilog_srcs = \
	src/verilog/clocking.vh \
	src/verilog/midas_wrapper.v \
	src/verilog/FPGATop.sv \

default: project



# Specialize sources for board
# ------------------------------------------------------------------------------
src/tcl/create_project.tcl: $(common)/midas_zynq.tcl Makefile
	sed 's/BOARD_NAME_HERE/$(BOARD)/g;s/PART_NUMBER_HERE/$(PART)/g' \
		$(common)/midas_zynq.tcl > src/tcl/create_project.tcl

src/tcl/make_bitstream.tcl: $(common)/make_bitstream.tcl
	cp -f $< $@

# Project generation
# ------------------------------------------------------------------------------
project = project/$(proj_name).xpr
$(project): | src/tcl/create_project.tcl
	vivado -mode tcl -source src/tcl/create_project.tcl;
project: $(project)

vivado: $(project)
	vivado $(project) &

bitstream = project/$(proj_name).runs/impl_1/midas_wrapper.bit
$(bitstream): src/tcl/make_bitstream.tcl $(verilog_srcs) src/constrs/base.xdc | $(project)
	vivado -mode tcl -source src/tcl/make_bitstream.tcl

bitstream: $(bitstream)


# Handle images and git submodule for prebuilt modules
# ------------------------------------------------------------------------------
images = boot.bif

fpga-images-$(BOARD)/boot.bin: $(images) $(bitstream)
	ln -sf ../../$(bitstream) fpga-images-$(BOARD)/boot_image/midas_wrapper.bit
	cd fpga-images-$(BOARD); bootgen -image ../$< -w -o boot.bin

load-sd: $(images)
	$(base_dir)/common/load_card.sh $(SD)

ramdisk-open: $(images)
	mkdir ramdisk
	dd if=fpga-images-$(BOARD)/uramdisk.image.gz  bs=64 skip=1 | \
	gunzip -c | sudo sh -c 'cd ramdisk/ && cpio -i'

ramdisk-close:
	@if [ ! -d "ramdisk" ]; then \
		echo "No ramdisk to close (use make ramdisk-open first)"; \
		exit 1; \
	fi
	sh -c 'cd ramdisk/ && sudo find . | sudo cpio -H newc -o' | gzip -9 > uramdisk.cpio.gz
	mkimage -A arm -O linux -T ramdisk -d uramdisk.cpio.gz fpga-images-$(BOARD)/uramdisk.image.gz
	rm uramdisk.cpio.gz
	@echo "Don't forget to remove ramdisk before opening it again (sudo rm -rf ramdisk)"

clean:
	rm -f *.log *.jou *.str
	rm -rf project
	rm -f src/tcl/create_project.tcl
	rm -f src/tcl/make_bitstream.tcl

.PHONY: vivado midas fetch-images load-sd ramdisk-open ramdisk-close clean
.PRECIOUS: src/verilog/clocking.vh
