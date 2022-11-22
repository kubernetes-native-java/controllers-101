#!/usr/bin/env bash
mvn spring-javaformat:apply && mvn -Pnative native:compile  && ./target/controllers-101
