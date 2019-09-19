/bin/busybox --install -s

# Load platform drivers
sh /loadDrivers.sh

# Mount the /proc and /sys filesystems.
mount -t proc none /proc
mount -t sysfs none /sys
mount -t devtmpfs none /dev

if [ -e /dev/iceblk ]; then
  mount -o ro /dev/iceblk /mnt/root
elif [ -e /dev/vda ]; then
  mount -o ro /dev/vda /mnt/root
else
  echo "Failed to load an appropriate block device. Dropping into emergency shell:"
  exit 1
fi

umount /proc
umount /sys
umount /dev
