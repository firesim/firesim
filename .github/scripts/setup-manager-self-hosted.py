#!/usr/bin/env python3

import traceback
import time
import requests
import sys

from fabric.api import *
import fabric

from common import *
# This is expected to be launch from the ci container
from ci_variables import *

def wait_machine_launch_complete():
    # Catch any exception that occurs so that we can gracefully teardown
    try:
        # wait until machine launch is complete
        with settings(warn_only=True):
            rc = run("timeout 20m grep -q '.*machine launch script complete.*' <(tail -f /machine-launchstatus)").return_code
            if rc != 0:
                run("cat /machine-launchstatus.log")
                raise Exception("machine-launch-script.sh failed to run")
    except BaseException as e:
        traceback.print_exc(file=sys.stdout)
        terminate_workflow_instances(ci_personal_api_token, ci_workflow_run_id)
        sys.exit(1)

def setup_self_hosted_runners():
    """ Installs GHA self-hosted runner machinery on the manager.  """

    # Catch any exception that occurs so that we can gracefully teardown
    try:
        # get the runner version based off the latest tag on the github runner repo
        RUNNER_VERSION = local("git ls-remote --refs --tags https://github.com/actions/runner.git | cut --delimiter='/' --fields=3 | tr '-' '~' | sort --version-sort | tail --lines=1", capture=True)
        RUNNER_VERSION = RUNNER_VERSION.replace("v", "")
        print("Using Github Actions Runner v{}".format(RUNNER_VERSION))
        # create NUM_RUNNER self-hosted runners on the manager that run in parallel
        NUM_RUNNERS = 4

        # verify no existing runners are running and remove unused runners
        with settings(warn_only=True):
            for runner_idx in range(NUM_RUNNERS):
                run(f"screen -XS gh-a-runner-{runner_idx} quit")
        deregister_runners(ci_personal_api_token, ci_workflow_run_id)

        # spawn runners
        for runner_idx in range(NUM_RUNNERS):
            actions_dir = "{}/actions-runner-{}".format(manager_home_dir, runner_idx)
            run("rm -rf {}".format(actions_dir))
            run("mkdir -p {}".format(actions_dir))
            with cd(actions_dir):
                run("curl -o actions-runner-linux-x64-{}.tar.gz -L https://github.com/actions/runner/releases/download/v{}/actions-runner-linux-x64-{}.tar.gz".format(RUNNER_VERSION, RUNNER_VERSION, RUNNER_VERSION))
                run("tar xzf ./actions-runner-linux-x64-{}.tar.gz".format(RUNNER_VERSION))

                # install deps
                run("sudo ./bin/installdependencies.sh")

                # get registration token from API
                r = requests.post(f"{gha_runners_api_url}/registration-token", headers=get_header(ci_personal_api_token))
                if r.status_code != 201:
                    raise Exception("HTTPS error: {} {}".format(r.status_code, r.json()))

                res_dict = r.json()
                reg_token = res_dict["token"]

                # config runner
                put(".github/scripts/gh-a-runner.expect", actions_dir)
                run("chmod +x gh-a-runner.expect")
                runner_name = f"{ci_workflow_run_id}-{ci_workflow_run_retries}-{runner_idx}" # used to teardown runner
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
    execute(wait_machine_launch_complete, hosts=[manager_hostname(ci_workflow_run_id)])
    # after we know machine-launch-script.sh is done, we need to logout and log back in
    fabric.network.disconnect_all()
    execute(setup_self_hosted_runners, hosts=[manager_hostname(ci_workflow_run_id)])
