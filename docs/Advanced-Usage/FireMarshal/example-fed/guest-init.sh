#!/bin/bash

echo "Installing the real time tool (not the shell builtin)"
dnf install -y time

echo "Installing the spambayes python module for the spam benchmark"
pip install spambayes

poweroff
