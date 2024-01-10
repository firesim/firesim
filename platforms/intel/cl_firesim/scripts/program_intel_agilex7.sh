#!/usr/bin/env bash

#
# Programming with Quartus Prime Programmer via JTAG
#
# This script launches Intel Quartus Prime Programmer to program 'sof' or 'jic' file via JTAG.
# Program files are searched for in the current directory or given as an argument.
#
# NOTE:  jtagconfig.exe -n -- for describe current status information
#
# TODO: add handler for error: Error (213019): Can't scan JTAG chain. Error code 87.
# for example latch command: 'jtagconfig.exe'

#  Time-stamp: <2022-12-16 10:48:49>

echo "+------------------------------------------------------------------------+"
echo "|  Quartus Prime Programmer ( JTAG )                                     |"
echo "+------------------------------------------------------------------------+"

read_yesno () {
    while :
    do
        echo "$* (y/n)?"
        read -r yn

        case $yn in
            y|Y|yes|Yes|YES)
                return 0
                ;;
            n|N|no|No|NO)
                return 1
                ;;
            *)
                echo Please answer Yes or No.
                ;;
        esac
    done
}

usage() {
    SCRIPT_NAME="${BASH_SOURCE##*/}"
    echo "Usage:"
    echo "  $SCRIPT_NAME [-1..9|-c=1..9] [-a|--auto] [prg-file-name] "
    echo ""
    echo "ARGS:"
    echo "    <prg-file-name>"
    echo "            File name for programming."
    echo "OPTIONS:"
    echo "    -1..9|-c=1..9"
    echo "            Cable index from 'jtagconfig'."
    echo "    -a|--auto"
    echo "            Automatic programming start if only one file is find."
    echo "Examples:"
    echo "  $SCRIPT_NAME"
    echo "  $SCRIPT_NAME path/project.sof"
    echo "  $SCRIPT_NAME -2 path/project.sof"
    echo "  $SCRIPT_NAME path/project.sof -1"
    echo "  export QP_CABLE_NAME=USB-Blaster[USB-0] # set environment variable for Bash."
}

if [ "$#" -gt 3 ] || [ "$1" == '-h' ] || [ "$1" == '--help' ] || [ "$1" == '-help' ]; then
    usage
    exit 0
fi

# Cable name
CNAME=""

get_cable_name () {
    index="$1"
    if ! hash jtagconfig 2>/dev/null; then
        echo "ERROR! 'jtagconfig' was not found in PATH!"
        exit 4
    fi
    IFS=$'\n' cable_arr=($(jtagconfig | sed -n 's/^[0-9]\+)\s\+// p'))
    RC=$?
    if [ "$RC" != 0 ]; then
        echo "ERROR! Error during launch 'jtagconfig'!"
        exit 5
    fi
    ARR_SIZE=${#cable_arr[@]}
    if [ "$ARR_SIZE" -eq 0 ]; then
        echo "ERROR! Can not find JTAG cable!"
        exit 6
    fi
    # FIXME: remove index decrement
    ((index--))  # 'jtagconfig' return list from '1'; cable_arr begin from '0'
    if [ "$index" -gt "$ARR_SIZE" ] || [ "$index" -lt 0 ]; then
        echo "ERROR! Wrong cable index specified! See 'jtagconfig'."
        echo ""
        usage
        exit 7
    fi
    CNAME="${cable_arr[$index]}"
}

ARG1=$(echo "$1" | sed -n "s/^-\(c=\)\?\([0-9]\{1,2\}\)$/\2/ p")
ARG2=$(echo "$2" | sed -n "s/^-\(c=\)\?\([0-9]\{1,2\}\)$/\2/ p")
ARG3=$(echo "$3" | sed -n "s/^-\(c=\)\?\([0-9]\{1,2\}\)$/\2/ p")

if [ -n "$ARG1" ]; then
    get_cable_name "$ARG1"
elif [ -n "$ARG2" ]; then
    get_cable_name "$ARG2"
elif [ -n "$ARG3" ]; then
    get_cable_name "$ARG3"
elif [ -n "$QP_CABLE_NAME" ]; then
    CNAME="$QP_CABLE_NAME"
fi

# File name
FNAME=""
if [ "$#" -eq 1 ] || [ "$#" -eq 2 ]; then
    FNAME_ARG1="$1"
    FNAME_ARG2="$2"
    FNAME_ARG3="$3"
    if [ -s "$1" ]; then
        FNAME="$FNAME_ARG1"
    elif [ -s "$2" ]; then
        FNAME="$FNAME_ARG2"
    elif [ -s "$3" ]; then
        FNAME="$FNAME_ARG3"
    fi
fi

get_file_name () {
    IFS=$'\n' f_arr=($(find . -type f \( -name "*.sof" -o -name "*.jic" \)))

    file_num=0
    if [ ${#f_arr[@]} -gt 0 ]; then
        echo " file(s) for programming:"
        if [ ${#f_arr[@]} -eq 1 ]; then
            file_num=0
            echo "${f_arr[$file_num]}"
        else
            cnt=1
            for i in "${f_arr[@]}"; do
                echo "$cnt: $i"
                ((cnt++))
            done
            echo "Select file :"
            read -r file_num
            ((file_num--))
        fi
    else
        echo "ERROR! There must be at least one *.sof or *.jic file in dir: $(pwd)"
        exit 2
    fi
    f_name="${f_arr[$file_num]}"

    if [ ! -s "$f_name" ]; then
        echo "ERROR! Can not open selected file!"
    else
        FNAME=$f_name
    fi
}

# Automatic programming
AUTOPGM=0
if [ "$1" = "-a" ] || [ "$1" = "--auto" ] \
       || [ "$2" = "-a" ] || [ "$2" = "--auto" ] \
       || [ "$3" = "-a" ] || [ "$3" = "--auto" ]; then
    AUTOPGM=1
fi


# if the path to the file is specified as script argument, programming is started without request
if [ -z "$FNAME" ]; then
    get_file_name
    if [ "$AUTOPGM" -eq 0 ]; then
        if ! read_yesno "Would you like load file: $FNAME" ; then
            echo "Exit without load!";
            exit 0;
        fi
    fi
fi

FTIME=$(date +%c -r "$FNAME")
echo "File: $FNAME ($FTIME)"
echo "Started Programmer operation ..."

if [[ "$f_name" == *.jic ]]; then
    # Blank-checking device(s), Performing CRC verification on device(s)
    quartus_pgm -c "$CNAME" -m jtag -o pvbi\;"$FNAME"
else
    quartus_pgm -c "$CNAME" -m jtag -o p\;"$FNAME"
fi

RC=$?
RED="\e[31m\e[1m"
GREEN="\e[32m\e[1m"
NORMAL="\e[0m"
if [ "$RC" != 0 ]; then
    printf "${RED}Programming Error!${NORMAL}\n"
else
    printf "${GREEN}Programming successful!${NORMAL}\n"
fi

exit "$RC"

#  This is for the sake of Emacs.
#  Local Variables:
#  time-stamp-end: "$"
#  time-stamp-format: "<%:y-%02m-%02d %02H:%02M:%02S>"
#  time-stamp-start: "Time-stamp: "
#  End:
