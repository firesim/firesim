ifneq ($(KERNELRELEASE),)

obj-m += rvgen.o

else

# The default assumes you cloned this as part of firesim-software (FireMarshal)
LINUXSRC=../../../../riscv-linux

KMAKE=make -C $(LINUXSRC) ARCH=riscv CROSS_COMPILE=riscv64-unknown-linux-gnu- M=$(PWD)

rvgen.ko: rvgen.c
	$(KMAKE)

clean:
	$(KMAKE) clean

endif
