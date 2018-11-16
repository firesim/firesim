RAWURL=http://fedora-riscv.tranquillity.se/kojifiles/work/tasks/5480/115480/Fedora-Developer-Rawhide-20181015.n.0-sda.raw.xz
COMPIMG=Fedora-Developer-Rawhide-20181015.n.0-sda.raw.xz
RAWIMG=Fedora-Developer-Rawhide-20181015.n.0-sda.raw
NEWIMG=rootfs.img
COPYSRC=firesim-net.service start-firesim-network.sh getty@.service

rootfs.cpio: $(NEWIMG) mk_cpio.sh
	./mk_cpio.sh $(NEWIMG) $@ 
	# touch it to make it newer than NEWIMG so that Make gets dependencies right
	touch $@

$(NEWIMG): $(RAWIMG) $(COPYSRC) convert_raw.sh
	./convert_raw.sh $(RAWIMG) $(NEWIMG)

$(RAWIMG): $(COMPIMG)
	unxz -k $(COMPIMG)

$(COMPIMG):
	wget $(RAWURL)
