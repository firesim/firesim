#!/bin/bash
# This script provides a quick and dirty means to capture the state of the
# firesim repo and its submodules, when launching a build so that it can be
# recreated manually later.

set -e

# if not empty, then you are in a submodule and this is the absolute path
# to the repo above you, iteratively cd to the topmost repo
super_root="$(git rev-parse --show-superproject-working-tree)"
while [[ -n  "$super_root" ]]; do
    cd "$super_root"
    super_root="$(git rev-parse --show-superproject-working-tree)"
done

git --no-pager log -n 1

if [[ -n $(git status -s)  ]]; then
    echo -e "\nRepo is dirty. Diff of tracked files follows.\n"
    # NB: The --submodule command is not supported in older git versions
    git --no-pager diff --submodule=diff || true
else
    echo -e "\nRepo is clean"
fi
