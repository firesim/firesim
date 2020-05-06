#!/bin/bash

echo "From create-file.sh script:" >> ${1}.txt
echo $@ >> ${1}.txt
