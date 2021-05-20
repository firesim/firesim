#!/usr/bin/env python

import traceback

from fabric.api import *

from common import *
# This is expected to be launch from the ci container
from ci_variables import *

def initialize_manager(max_runtime):
    """ Performs the prerequisite tasks for all CI jobs that will run on the manager instance

    max_runtime (hours): The maximum uptime this manager and its associated
        instances should have before it is stopped. This serves as a redundant check 
        in case the workflow-monitor is brought down for some reason.
    """

    # Catch any exception that occurs so that we can gracefully teardown
    try:
        put(ci_workdir + "/scripts/machine-launch-script.sh", manager_home_dir)
        with cd(manager_home_dir):
            run("chmod +x machine-launch-script.sh")
            run("sudo ./machine-launch-script.sh")
            run("git clone https://github.com/firesim/firesim.git")

        with cd(manager_fsim_dir):
            run("git checkout " + ci_commit_sha1)
            run("./build-setup.sh --fast")

        with cd(manager_fsim_dir), prefix("source ./sourceme-f1-manager.sh"):
            run(".circleci/firesim-managerinit.expect {} {} {}".format(
                os.environ["AWS_ACCESS_KEY_ID"],
                os.environ["AWS_SECRET_ACCESS_KEY"],
                os.environ["AWS_DEFAULT_REGION"]))

        with cd(manager_ci_dir):
            # Put a baseline time-to-live bound on the manager.
            # Instances will be stopped and cleaned up in a nightly job.

            # Setting pty=False is required to stop the screen from being
            # culled when the SSH session associated with teh run command ends.
            run("screen -S ttl -dm bash -c \'sleep {}; ./change-workflow-instance-states.py {} stop\'".format(int(max_runtime) * 3600 , ci_workflow_id), pty=False)
            run("screen -S workflow-monitor -dm ./workflow-monitor.py {} {}".format(ci_workflow_id, ci_api_token), pty=False)

    except BaseException as e:
        traceback.print_exc(file=sys.stdout)
        terminate_workflow_instances(ci_workflow_id)
        sys.exit(1)

if __name__ == "__main__":
    max_runtime = sys.argv[1]
    execute(initialize_manager, max_runtime, hosts=[manager_hostname(ci_workflow_id)])
