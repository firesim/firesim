RAWURL=https://dl.fedoraproject.org/pub/alt/risc-v/disk-images/fedora/rawhide/20190126.n.0/Developer/Fedora-Developer-Rawhide-20190126.n.0-sda1.raw.xz
COMPIMG=rootfs.img.xz
NEWIMG=rootfs.img

$(NEWIMG): $(COMPIMG)
	unxz -k $(COMPIMG)

$(COMPIMG):
	curl $(RAWURL) -o $(COMPIMG)

clean:
	rm -f $(COMPIMG) $(RAWIMG) $(NEWIMG)
