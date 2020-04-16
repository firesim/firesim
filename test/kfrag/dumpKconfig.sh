#!/bin/bash
set -e

gunzip -kc /proc/config.gz | grep $1
