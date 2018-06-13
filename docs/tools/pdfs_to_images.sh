#!/usr/bin/env bash

# this converts PDF images to pngs for use in docs
# requires that you install imagemagick
# sudo yum -y install ImageMagick

set -o xtrace

BASEPATH=$1

function outputimage() {
    # first arg is filename, second arg is output name
    convert -density 600 $1 -quality 100 $2.png
}

function get_path() {
    # in the base directory given as first arg, return the path of the file
    # with filename given in the second arg
    FNAMEPATH=$(find $1  -type f -name "*$2")
    echo $FNAMEPATH
}

function producepng() {
    # first arg is pdf filename
    SOURCEPATH=$(get_path $1 $2)
    outputimage $SOURCEPATH $2
}


# $1 is starting path

GOTPATH=$(get_path $1 "95th-memcached-request-latency.pdf")
echo $GOTPATH


producepng $BASEPATH "95th-memcached-request-latency.pdf"
