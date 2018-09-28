#!/bin/bash

echo "Testing the response back and forth"

#Get the IP Address of the target and use 8080 as the Port
IPADDR_TARGET=$(ip addr show ens3 | grep 'inet ' | awk '{print $2}' | cut -f1 -d'/')
#Right now this only works with port 8080
PORTNO=8080
TIME_SEC=1

echo "Ping IPADDR=$IPADDR_TARGET at PORT=$PORTNO"
./a.out $PORTNO $IPADDR_TARGET $TIME_SEC

echo "Done"
