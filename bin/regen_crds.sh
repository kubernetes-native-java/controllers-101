#!/usr/bin/env bash

# the value u pass to -n and -p (io.spring) HAS to match the value
# in the crd definition itself, but reversed. So, in the foo.yaml we
# have `group: spring.io`, and this Java package becomes io.spring
CURRENT_DIR=$(cd `dirname $0` && pwd)
LOCAL_MANIFEST_FILE=${CURRENT_DIR}/foo.yaml
echo "CURRENT_DIR=${CURRENT_DIR}"
echo "LOCAL_MANIFEST_FILE=${LOCAL_MANIFEST_FILE}"
mkdir -p /tmp/java && cd /tmp/java
docker run \
  --rm \
  -v "$LOCAL_MANIFEST_FILE":"$LOCAL_MANIFEST_FILE" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd)":"$(pwd)" \
  -ti \
  --network host \
  ghcr.io/kubernetes-client/java/crd-model-gen:v1.0.6 \
  /generate.sh \
  -u $LOCAL_MANIFEST_FILE \
  -n io.spring \
  -p io.spring \
  -o "$(pwd)"
cp -r /tmp/java/src/main/java/io/spring/models  ${CURRENT_DIR}/../src/main/java/io/spring/
