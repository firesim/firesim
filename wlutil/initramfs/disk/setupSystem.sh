/bin/busybox --install -s

# Mount the /proc and /sys filesystems.
mount -t proc none /proc
mount -t sysfs none /sys
mount -t devtmpfs none /dev

# Load all kernel modules
modprobe -a $(modprobe -l)

if [ -e /dev/iceblk ]; then
  echo "Mounting /dev/iceblk as root device"
  mount -o ro /dev/iceblk /mnt/root
elif [ -e /dev/vda ]; then
  echo "Mounting /dev/vda as root device"
  mount -o ro /dev/vda /mnt/root
else
  echo "Failed to load an appropriate block device. Dropping into emergency shell:"
  exit 1
fi

umount /proc
umount /sys
umount /dev
