ifneq ($(KERNELRELEASE),)

obj-m += icenet.o

else

KMAKE=make -C ../riscv-linux ARCH=riscv CROSS_COMPILE=riscv64-unknown-linux-gnu- M=$(PWD)

icenet.ko: icenet.c
	$(KMAKE)

clean:
	$(KMAKE) clean

endif
