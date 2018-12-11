#!/bin/bash
set -e

VERSION=`cat stuncheck/VERSION`
SV="stuncheck-${VERSION}"

docker build --build-arg VERSION=$VERSION -t stuncheck:latest .


docker tag stuncheck:latest readytalk/stuncheck:${VERSION}
docker tag stuncheck:latest readytalk/stuncheck:latest

if [[ ${TRAVIS} && "${TRAVIS_BRANCH}" == "master" && -n $DOCKER_USERNAME && -n $DOCKER_PASSWORD ]]; then
  docker login -u="$DOCKER_USERNAME" -p="$DOCKER_PASSWORD"
  docker push readytalk/stuncheck:${VERSION}
  docker push readytalk/stuncheck:latest
fi



