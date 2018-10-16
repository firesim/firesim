all: fedora buildroot

fedora: fedora-rootfs.img fedora-bin

buildroot: buildroot-rootfs.img buildroot-bin

%-rootfs.img %-bin: %/linux-config
	./build.sh $*

%-rootfs.img: %-bin
