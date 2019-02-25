#!/bin/bash
# Usage: ./resize.sh FEDORA_IMAGE NEW_SIZE_IN_BYTES

IMG=$1
NEWSZ=$(numfmt --from=iec $2)
REALSZ=$(du --apparent-size -b $IMG | cut -f1)

if [ $NEWSZ -le $REALSZ ]; then
  echo "Target size smaller than current size (shrinking images not currently supported)"
  exit
fi

truncate -s $NEWSZ $IMG
e2fsck -f $IMG
resize2fs $IMG
