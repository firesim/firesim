ifneq ($(KERNELRELEASE),)

obj-m += iceblk.o

else

# The default assumes you cloned this as part of firesim-software (FireMarshal)
LINUXSRC=../../../../riscv-linux

KMAKE=make -C $(LINUXSRC) ARCH=riscv CROSS_COMPILE=riscv64-unknown-linux-gnu- M=$(PWD)

iceblk.ko: iceblk.c
	$(KMAKE)

clean:
	$(KMAKE) clean

endif
