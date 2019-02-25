#!/bin/bash
# This script provides a quick and dirty means to capture the state of the
# firesim repo and its submodules, when launching a build so that it can be
# recreated manually later.
git --no-pager log -n 1

if [[ -n $(git status -s)  ]]; then
    echo -e "\nRepo is dirty. Diff of tracked files follows.\n"
    git --no-pager diff --submodule=diff
else
    echo -e "\nRepo is clean"
fi
