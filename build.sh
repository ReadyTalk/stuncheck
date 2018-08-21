#!/bin/bash
set -e

cp tmplt.Dockerfile Dockerfile

VERSION=`cat stuncheck/VERSION`
SV="stuncheck-${VERSION}"

sed -i -e 's/{VERSION}/'"${VERSION}"'/g' Dockerfile

docker build -t stuncheck:latest .

rm ./Dockerfile

docker tag stuncheck:latest readytalk/stuncheck:${VERSION}
docker tag stuncheck:latest readytalk/stuncheck:latest

if [[ ${TRAVIS} && "${TRAVIS_BRANCH}" == "master" && -n $DOCKER_USERNAME && -n $DOCKER_PASSWORD ]]; then
  docker login -u="$DOCKER_USERNAME" -p="$DOCKER_PASSWORD"
  docker push readytalk/stuncheck:${VERSION}
  docker push readytalk/stuncheck:latest
fi



