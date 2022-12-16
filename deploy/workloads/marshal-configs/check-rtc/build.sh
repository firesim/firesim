#!/bin/bash

pushd ../../../../sw/check-rtc
make check-rtc
popd
mkdir -p overlay
cp ../../../../sw/check-rtc/check-rtc overlay/
