#!/usr/bin/env bash

# this starts an agent if it isn't running
# and adds firesim.pem if there's no key setup

# adapted from https://stackoverflow.com/a/48509425
# Ensure agent is running

_no_agent() {
    # NOTE: Ignore agent forwarding if running within tmux or screen, as
    # detaching a session and logging out closes the agent socket.
    { test -n "${TMUX}${STY}" && test -z "${SSH_AGENT_PID}" ; } ||
    { ssh-add -l >/dev/null 2>&1 ; test $? -eq 2 ; }
}

if _no_agent ; then
    {
        flock -x 3
        # Load cached agent connection info.
        source /dev/fd/3

        if _no_agent ; then
            # Start agent and cache agent connection info.
            eval "$(umask 066 && ssh-agent -s 3>&- | tee /dev/fd/3)" >/dev/null
        fi
    } 3<> ~/.ssh-agent
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
