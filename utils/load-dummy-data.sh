#!/bin/bash

for i in $(seq 1 10)
do
  kafka-console-producer --broker-list broker:29092 --topic input-test-topic < /dummy-data/test.json
done
