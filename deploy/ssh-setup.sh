#!/usr/bin/env bash

# this starts an agent if it isn't running
# and adds firesim.pem if there's no key setup

# adapted from https://stackoverflow.com/a/48509425
# Ensure agent is running
ssh-add -l &>/dev/null
if [ $? -eq 2 ]; then
    # Could not open a connection to your authentication agent.

    # Load stored agent connection info.
    test -r ~/.ssh-agent && \
        eval "$(<~/.ssh-agent)" >/dev/null

    ssh-add -l &>/dev/null
    if [ $? -eq 2 ]; then
        # Start agent and store agent connection info.
        (umask 066; ssh-agent > ~/.ssh-agent)
        eval "$(<~/.ssh-agent)" >/dev/null
    fi
fi

# if key is available, print success, else add it
if ssh-add -l | grep -q 'firesim\.pem'; then
    echo "success: firesim.pem available in ssh-agent"
else
    if ssh-add ~/firesim.pem; then
        echo "success: firesim.pem added to ssh-agent"
    else
        echo "FAIL: ERROR adding ~/firesim.pem to ssh-agent. does it exist?"
    fi
fi
