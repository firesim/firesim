READELF=riscv64-unknown-linux-gnu-readelf

BBL=~/firesim/sw/firesim-software/riscv-pk/build/bbl
VMLINUX=~/firesim/sw/firesim-software/riscv-linux/vmlinux
RUNTIME=~/firesim/sw/firesim-software/sdk/runtime/eyrie-rt

OUTPUT=symbols.txt

function parseELF {
	$READELF --symbols $1 |grep 'LOCAL\|GLOBAL' | grep -v 'FILE\|UND\|SECTION' | awk -vfrom=$3 '{print $2 "," $3 "," from "," $8}' >> $2
}

cat /dev/null > $OUTPUT
parseELF $BBL $OUTPUT sm
parseELF $VMLINUX $OUTPUT linux 
parseELF $RUNTIME $OUTPUT runtime


