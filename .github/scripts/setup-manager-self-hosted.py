#!/usr/bin/env python3

import traceback
import time
import requests
import sys

from fabric.api import *

from common import *
# This is expected to be launch from the ci container
from ci_variables import *

def initialize_manager_hosted():
    """ Performs the prerequisite tasks for all CI jobs that will run on the manager instance

    max_runtime (hours): The maximum uptime this manager and its associated
        instances should have before it is stopped. This serves as a redundant check
        in case the workflow-monitor is brought down for some reason.
    """

    # Catch any exception that occurs so that we can gracefully teardown
    try:
        # wait until machine launch is complete
        with cd(manager_home_dir):
            with settings(warn_only=True):
                rc = run("timeout 20m grep -q '.*machine launch script complete.*' <(tail -f machine-launchstatus)").return_code
                if rc != 0:
                    run("cat machine-launchstatus.log")
                    raise Exception("machine-launch-script.sh failed to run")

        # get the runner version based off the latest tag on the github runner repo
        RUNNER_VERSION = local("git ls-remote --refs --tags https://github.com/actions/runner.git | cut --delimiter='/' --fields=3 | tr '-' '~' | sort --version-sort | tail --lines=1", capture=True)
        RUNNER_VERSION = RUNNER_VERSION.replace("v", "")
        print("Using Github Actions Runner v{}".format(RUNNER_VERSION))
        # create NUM_RUNNER self-hosted runners on the manager that run in parallel
        NUM_RUNNERS = 4
        for runner_idx in range(NUM_RUNNERS):
            actions_dir = "{}/actions-runner-{}".format(manager_home_dir, runner_idx)
            run("mkdir -p {}".format(actions_dir))
            with cd(actions_dir):
                run("curl -o actions-runner-linux-x64-{}.tar.gz -L https://github.com/actions/runner/releases/download/v{}/actions-runner-linux-x64-{}.tar.gz".format(RUNNER_VERSION, RUNNER_VERSION, RUNNER_VERSION))
                run("tar xzf ./actions-runner-linux-x64-{}.tar.gz".format(RUNNER_VERSION))

                # install deps
                run("sudo ./bin/installdependencies.sh")

                # get registration token from API
                headers = {'Authorization': "token {}".format(ci_personal_api_token.strip())}
                r = requests.post("https://api.github.com/repos/firesim/firesim/actions/runners/registration-token", headers=headers)
                if r.status_code != 201:
                    raise Exception("HTTPS error: {} {}. Retrying.".format(r.status_code, r.json()))

                res_dict = r.json()
                reg_token = res_dict["token"]

                # config runner
                put(".github/scripts/gh-a-runner.expect", actions_dir)
                run("chmod +x gh-a-runner.expect")
                runner_name = "{}-{}".format(ci_workflow_run_id, runner_idx) # used to teardown runner
                unique_label = ci_workflow_run_id # used within the yaml to choose a runner
                run("./gh-a-runner.expect {} {} {}".format(reg_token, runner_name, unique_label))

                # start runner
                # Setting pty=False is required to stop the screen from being
                # culled when the SSH session associated with the run command ends.
                run("screen -S gh-a-runner-{} -L -dm ./run.sh".format(runner_idx), pty=False)

                # double check that screen is setup properly
                with settings(warn_only=True):
                    rc = run("screen -ls | grep \"gh-a-runner-{}\"".format(runner_idx)).return_code
                    if rc != 0:
                        run("cat screenlog.*")
                        raise Exception("There was an issue with setting up Github Actions runner {}".format(runner_idx))

    except BaseException as e:
        traceback.print_exc(file=sys.stdout)
        terminate_workflow_instances(ci_personal_api_token, ci_workflow_run_id)
        sys.exit(1)

if __name__ == "__main__":
    execute(initialize_manager_hosted, hosts=[manager_hostname(ci_workflow_run_id)])
