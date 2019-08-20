# RAWURL=http://fedora-riscv.tranquillity.se/kojifiles/work/tasks/5480/115480/Fedora-Developer-Rawhide-20181015.n.0-sda.raw.xz
RAWURL=https://dl.fedoraproject.org/pub/alt/risc-v/disk-images/fedora/rawhide/20190126.n.0/Developer/Fedora-Developer-Rawhide-20190126.n.0-sda1.raw.xz
COMPIMG=rootfs.raw.xz
RAWIMG=rootfs.raw
NEWIMG=rootfs.img

$(NEWIMG): $(RAWIMG)
	./convert_raw.sh $(RAWIMG) $(NEWIMG)

$(RAWIMG): $(COMPIMG)
	unxz -k $(COMPIMG)

$(COMPIMG):
	curl $(RAWURL) -o $(COMPIMG)

clean:
	rm -f $(COMPIMG) $(RAWIMG) $(NEWIMG)
