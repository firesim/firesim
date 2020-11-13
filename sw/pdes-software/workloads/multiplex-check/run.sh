#! /bin/sh
set -x

cf_dir=/sys/devices/system/cpu/cpu0/cpufreq
echo userspace >> $cf_dir/scaling_governor
cat $cf_dir/scaling_governor
cat $cf_dir/scaling_max_freq
cat $cf_dir/scaling_min_freq
cat $cf_dir/scaling_cur_freq

cat $cf_dir/scaling_min_freq >> $cf_dir/scaling_setspeed
./check-rtc-linux

cat $cf_dir/scaling_max_freq >> $cf_dir/scaling_setspeed
./check-rtc-linux
