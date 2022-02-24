IP_ADDR_FILE=$SCRIPT_DIR/ip_address

parse_ip_address () {
    IP_ADDR=$(grep -E -o "192\.168\.[0-9]{1,3}\.[0-9]{1,3}" $IP_ADDR_FILE | head -n 1)
    IP_ADDR="centos@$IP_ADDR"
}

FIRESIM_PEM_FILE=~/firesim.pem

copy () {
    rsync -avzp -e "ssh -o StrictHostKeyChecking=no -i $FIRESIM_PEM_FILE" --exclude '.git' $1 $2
}

copy_no_sym () {
    rsync -avzpL -e "ssh -o StrictHostKeyChecking=no -i $FIRESIM_PEM_FILE" --exclude '.git' $1 $2
}

run () {
    if [ -z $IP_ADDR ]; then
        parse_ip_address
    fi
    ssh -i $FIRESIM_PEM_FILE -o "StrictHostKeyChecking no" -t $IP_ADDR "bash -l -c '$@'"
}

run_script () {
    if [ -z $IP_ADDR ]; then
        parse_ip_address
    fi
    ssh -i $FIRESIM_PEM_FILE -o "StrictHostKeyChecking no" -t $IP_ADDR 'bash -l -s' < $1 "$2"
}


