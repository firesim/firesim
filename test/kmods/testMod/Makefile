ifneq ($(KERNELRELEASE),)

obj-m += testmod.o

else

KMAKE=make -C $(LINUXSRC) ARCH=riscv CROSS_COMPILE=riscv64-unknown-linux-gnu- M=$(PWD)

testmod.ko: testmod.c
	$(KMAKE)

clean:
	$(KMAKE) clean

endif
