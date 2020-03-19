#!/bin/bash
set -e

TESTDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
pushd $TESTDIR

marshal test command.json

popd
