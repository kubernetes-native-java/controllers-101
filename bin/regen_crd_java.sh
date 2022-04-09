#!/usr/bin/env bash
#GROUP=${1:-foos.spring.io}
#CID=$(docker ps | grep "kindest/node:v1.21.1" | awk '{ print $1 }')
#docker rm -f $CID || echo "no need to reset Kind cluster..."
#SCRIPT_DIR=$(cd $(dirname $0)/.. && pwd)
#JAVA_DIR=/tmp/javaoutput/
#LOCAL_MANIFEST_FILE=${SCRIPT_DIR}/k8s/crds/foo.yaml
#mkdir -p $JAVA_DIR && cd $JAVA_DIR
#docker run \
#  --rm \
#  -v "$LOCAL_MANIFEST_FILE":"$LOCAL_MANIFEST_FILE" \
#  -v /var/run/docker.sock:/var/run/docker.sock \
#  -v "$JAVA_DIR":"$JAVA_DIR" \
#  -ti \
#  --network host \
#  docker.pkg.github.com/kubernetes-client/java/crd-model-gen:v1.0.6 \
#  /generate.sh \
#  -u $LOCAL_MANIFEST_FILE \
#  -n $GROUP \
#  -p $GROUP \
#  -o $JAVA_DIR

mkdir -p /tmp/java && cd /tmp/java
docker run \
  --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd)":"$(pwd)" \
  -ti \
  --network host \
  docker.pkg.github.com/kubernetes-client/java/crd-model-gen:v1.0.6 \
  /generate.sh \
  -u https://gist.githubusercontent.com/yue9944882/266fee8e95c2f15a93778263633e72ed/raw/be12c13379eeed13d2532cb65da61fffb19ee3e7/crontab-crd.yaml \
  -n com.example.stable \
  -p com.example.stable \
  -o "$(pwd)"
