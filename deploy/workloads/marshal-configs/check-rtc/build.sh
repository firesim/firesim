#!/bin/bash

pushd ../../../../sw/check-rtc
make check-rtc
popd
cp ../../../../sw/check-rtc/check-rtc .
