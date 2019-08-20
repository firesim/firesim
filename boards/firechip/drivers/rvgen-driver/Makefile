ifneq ($(KERNELRELEASE),)

obj-m += rvgen.o

else

KMAKE=make -C ../riscv-linux ARCH=riscv CROSS_COMPILE=riscv64-unknown-linux-gnu- M=$(PWD)

rvgen.ko: rvgen.c
	$(KMAKE)

clean:
	$(KMAKE) clean

endif
