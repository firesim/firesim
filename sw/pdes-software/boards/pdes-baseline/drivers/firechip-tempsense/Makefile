ifneq ($(KERNELRELEASE),)

obj-m += firechip-tempsense.o

else

# The default assumes you cloned this as part of firesim-software (FireMarshal)
LINUXSRC?=../../../../riscv-linux

KMAKE=make -C $(LINUXSRC) ARCH=riscv CROSS_COMPILE=riscv64-unknown-linux-gnu- M=$(PWD)

firechip-tempsense.ko: firechip-tempsense.c
	$(KMAKE)

clean:
	$(KMAKE) clean

endif
