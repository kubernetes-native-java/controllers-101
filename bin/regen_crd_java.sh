#!/usr/bin/env bash

URI=https://raw.githubusercontent.com/kubernetes-native-java/controllers-101/main/k8s/crds/foo.yaml
mkdir -p /tmp/java && cd /tmp/java
docker run \
  --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd)":"$(pwd)" \
  -ti \
  --network host \
  docker.pkg.github.com/kubernetes-client/java/crd-model-gen:v1.0.6 \
  /generate.sh \
  -u $URI \
  -n io.spring  \
  -p io.spring \
  -o "$(pwd)"
