#!/usr/bin/env bash

# Builds a CI docker image and pushes it to docker hub.
# See https://hub.docker.com/repository/docker/firesim/firesim-ci

if [[ $# -ne 1 ]]; then
    echo "$0 accepts one argument: a version tag for the docker image"
    exit 1
fi
tag="$1"
docker image build . -t firesim/firesim-ci:$tag
docker push firesim/firesim-ci:$tag
