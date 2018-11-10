rootfs.cpio: rootfs.img

.PHONY: rootfs.img
rootfs.img:
	cp buildroot-config buildroot/.config
	# Note: buildroot doesn't support make -jN, but it does parallelize anyway.
	make -C buildroot -j1
	cp buildroot/output/images/rootfs.ext2 rootfs.img
