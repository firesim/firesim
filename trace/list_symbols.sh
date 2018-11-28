OUTPUT=symbol.list

VMLINUX_OBJS="keystone.o \
	keystone-page.o \
	keystone-enclave.o \
	keystone-ioctl.o \
	keystone-rtld.o"

SM_OBJS="crypto.o \
	pmp.o \
	enclave.o \
	attest.o \
	sm.o \
	sm-sbi.o \
  page.o \
	thread.o \
	"


cat /dev/null > symbol.list

for obj in $VMLINUX_OBJS; do	
	echo $obj
	riscv64-unknown-linux-gnu-readelf --symbols \
		~/firesim/sw/firesim-software/riscv-linux/arch/riscv/drivers/$obj \
		| grep 'LOCAL\|GLOBAL' | grep -v 'UND\|FILE\|SECTION' | awk '{print $8}' | grep -v ".L"\
		>> symbol.list
done

for obj in $SM_OBJS; do
	echo $obj
	riscv64-unknown-linux-gnu-readelf --symbols \
		~/firesim/sw/firesim-software/riscv-pk/build/$obj \
		| grep 'LOCAL\|GLOBAL' | grep -v 'UND\|FILE\|SECTION' | awk '{print $8}' | grep -v ".L\|__func__"\
		>> symbol.list
done

