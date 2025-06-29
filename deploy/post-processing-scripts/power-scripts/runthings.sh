#!/usr/bin/env bash
#set -x

IDX_DIGITS=5 # Must be enough for all files
CYCLES=10000000
RAW_TRACE=../data/powerdatLATEST2.csv
DRAM_CONFIG=MIDAS-MICRON_2Gb_DDR3-2133_8bit.xml
DRAMPower=~/DRAMPower-4.0/drampower
TMP_DIR=tmps
OUT_DIR=output

num_files=$(($(wc -l $RAW_TRACE | awk '{print $1}') / $CYCLES + 1))

#mkdirs
mkdir -p $TMP_DIR
mkdir -p $OUT_DIR

# Clear fifos
rm -rf $TMP_DIR/*.fifo

## Make fifos and invoke python scripts
#for i in `seq -f "%0${IDX_DIGITS}g" 0 $(($num_files - 1))`; do
#  FIFO=$TMP_DIR/$i.fifo
#  mkfifo $FIFO
#  ./combined_power.py $DRAMPower $DRAM_CONFIG $FIFO $i $OUT_DIR &
#done

# Invoke split on the fifos
split -d -l $CYCLES -a $IDX_DIGITS --additional-suffix=.fifo --filter='cat > $FILE' $RAW_TRACE $TMP_DIR/ &

# Alternative non-fifo approach
for i in `seq -f "%0${IDX_DIGITS}g" 0 $(($num_files - 1))`; do
  FIFO=$TMP_DIR/$i.fifo
  # Wait for the file to exist
  while [ ! -f $FIFO ]; do
    sleep 1
  done
  ./combined_power.py $DRAMPower $DRAM_CONFIG $FIFO $i $OUT_DIR && rm -rf $FIFO &
done

#set +x
