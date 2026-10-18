#!/bin/sh
set -x
#sleep 60

java -jar solvis2mqtt.jar --server-path=/media/data/fhem &> /media/data/fhem/SolvisStdErr.log &
