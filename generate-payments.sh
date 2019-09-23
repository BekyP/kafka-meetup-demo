#!/bin/bash

for i in $(seq 1 $1)
do
	NEW_UUID_FROM=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 32 | head -n 1)

	for i in $(seq 1 $2)
	do
  		NEW_UUID_TO=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 32 | head -n 1)
  		AMOUNT=`shuf -i 0-5000 -n 1`
  		TIMESTAMP=$(echo '('`date +"%s.%N"` ' * 1000000)/1' | bc)
  		echo "{\"txnId\":\"pay-${TIMESTAMP}\",\"id\":\"${TIMESTAMP}\",\"from_person\":\"${NEW_UUID_FROM}\",\"to_person\":\"${NEW_UUID_TO}\",\"amount\":\"${AMOUNT}\",\"state\":\"incoming\",\"timestamp\":${TIMESTAMP}}" >> example.json
	done
done
