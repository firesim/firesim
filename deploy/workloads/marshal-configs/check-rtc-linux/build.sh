#!/bin/bash

pushd ../../../../sw/check-rtc
make check-rtc-linux
popd
mkdir -p overlay
cp ../../../../sw/check-rtc/check-rtc-linux overlay/
