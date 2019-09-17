#!/bin/bash

git clone https://github.com/big-data-europe/docker-hive.git &
git clone https://github.com/big-data-europe/docker-hbase.git &

wait

docker-compose -f docker-hive/docker-compose.yml up -d &
docker-compose -f docker-hbase/docker-compose-standalone.yml up -d &
docker-compose up -d &

wait

docker network connect docker-hbase_default kafka-meetup-demo_connect_1 &
docker network connect docker-hive_default kafka-meetup-demo_connect_1 &

wait
