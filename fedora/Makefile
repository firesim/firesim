RAWURL=http://fedora-riscv.tranquillity.se/kojifiles/work/tasks/5480/115480/Fedora-Developer-Rawhide-20181015.n.0-sda.raw.xz
COMPIMG=Fedora-Developer-Rawhide-20181015.n.0-sda.raw.xz
RAWIMG=Fedora-Developer-Rawhide-20181015.n.0-sda.raw
NEWIMG=rootfs.img

$(NEWIMG): $(RAWIMG)
	./convert_raw.sh $(RAWIMG) $(NEWIMG)

$(RAWIMG): $(COMPIMG)
	unxz -k $(COMPIMG)

$(COMPIMG):
	wget $(RAWURL)
