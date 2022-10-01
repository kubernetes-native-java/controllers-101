#!/usr/bin/env bash
mvn -DskipTests -Pnative clean package
./target/controllers-101