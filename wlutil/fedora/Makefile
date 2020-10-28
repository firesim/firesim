RAWURL=http://fedora.riscv.rocks/kojifiles/work/tasks/944/320944/Fedora-Developer-Rawhide-20200108.n.0-sda.raw.xz
COMPIMG=rootfs.img.xz
NEWIMG=rootfs.img

# Extract root partition without partition table
# NOTE: Offset must be adjusted for different base image
$(NEWIMG): $(COMPIMG)
	xzcat -k $(COMPIMG) | dd of=$(NEWIMG) bs=512 skip=1007616

$(COMPIMG):
	curl $(RAWURL) -o $(COMPIMG)

clean:
	rm -f $(COMPIMG) $(RAWIMG) $(NEWIMG)
