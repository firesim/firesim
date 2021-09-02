RAWURL=https://dl.fedoraproject.org/pub/alt/risc-v/repo/virt-builder-images/images/Fedora-Minimal-Rawhide-20191123.n.1-sda.raw.xz
COMPIMG=rootfs.img.xz
NEWIMG=rootfs.img

# Extract root partition without partition table
# NOTE: Offset must be adjusted for different base image
$(NEWIMG): $(COMPIMG)
	xzcat -k $(COMPIMG) | dd of=$(NEWIMG) bs=512 skip=1249280

$(COMPIMG):
	curl $(RAWURL) -o $(COMPIMG)

clean:
	rm -f $(COMPIMG) $(RAWIMG) $(NEWIMG)
