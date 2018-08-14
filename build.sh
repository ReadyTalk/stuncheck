#!/bin/bash

cp tmplt.Dockerfile Dockerfile

VERSION=`cat stuncheck/VERSION`
SV="stuncheck-${VERSION}"

sed -i -e 's/{VERSION}/'"${VERSION}"'/g' Dockerfile

docker build -t stuncheck-${VERSION}:latest .

docker tag stuncheck:latest readytalk/stuncheck:${SV}
docker tag stuncheck:latest readytalk/stuncheck:latest

if [[ ${TRAVIS} && "${TRAVIS_BRANCH}" == "master" && -n $DOCKER_USERNAME && -n $DOCKER_PASSWORD ]]; then
  docker login -u="$DOCKER_USERNAME" -p="$DOCKER_PASSWORD"
  docker push readytalk/stuncheck:${SV}
  docker push readytalk/stuncheck:latest
fi
rm ./Dockerfile



