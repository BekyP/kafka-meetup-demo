#!/bin/bash

git clone https://github.com/big-data-europe/docker-hive.git

mvn clean package -f ksql-udf-example/pom.xml &
mvn clean package -DskipTests -f nifi-custom-processor/code/nifi-custom/pom.xml &

wait

docker-compose -f docker-hive/docker-compose.yml up -d &
docker-compose up -d &

wait

docker network connect docker-hive_default kafka-meetup-demo_connect_1
