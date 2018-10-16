all: fedora buildroot

fedora: fedora-rootfs.img fedora-bin

br: buildroot

buildroot: br-rootfs.img br-bin

%-rootfs.img %-bin: %/linux-config
	./build.sh $*

%-rootfs.img: %-bin
