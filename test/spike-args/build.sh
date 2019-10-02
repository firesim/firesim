#!/bin/bash
set -e

pushd ../bare
make
popd

if [ ! -f hello ]; then
  ln -s ../bare/hello .
fi
