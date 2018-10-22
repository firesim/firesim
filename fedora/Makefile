RAWURL=http://fedora-riscv.tranquillity.se/kojifiles/work/tasks/5480/115480/Fedora-Developer-Rawhide-20181015.n.0-sda.raw.xz
COMPIMG=Fedora-Developer-Rawhide-20181015.n.0-sda.raw.xz
RAWIMG=Fedora-Developer-Rawhide-20181015.n.0-sda.raw
NEWIMG=rootfs.img

rootfs.cpio: $(NEWIMG)
	sudo mount -o loop $< disk-mount/
	sudo ln -s -f /sbin/init disk-mount/init
	sudo find ./disk-mount -print0 | sudo cpio --null -ov --format=newc > $@	
	sudo rm disk-mount/init
	sudo umount disk-mount/
	# Touch it so that make sees it as more recent than $(NEWIMG) 
	touch $@

$(NEWIMG): $(RAWIMG)
	./convert_raw.sh $(RAWIMG) $(NEWIMG)

$(RAWIMG): $(COMPIMG)
	unxz -k $(COMPIMG)

$(COMPIMG):
	wget $(RAWURL)
