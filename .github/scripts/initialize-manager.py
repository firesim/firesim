#!/usr/bin/env python3

import traceback
import time

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
        # wait until machine launch is complete
        with cd(manager_home_dir):
            # add firesim.pem
            with open(manager_fsim_pem, "w") as pem_file:
                pem_file.write(os.environ["FIRESIM_PEM"])
            local("chmod 600 {}".format(manager_fsim_pem))
            set_fabric_firesim_pem()

            # copy ci version of the repo into the new globally accessible location
            run("git clone {} {}".format(ci_workdir, manager_fsim_dir))

        with cd(manager_fsim_dir):
            run("./build-setup.sh --fast --skip-validate")

        # Initialize marshal submodules early because it appears some form of
        # contention between submodule initialization and the jgit SBT plugin
        # causes SBT to lock up, causing downstream scala tests to fail when
        # run concurrently with ./init-submodules.sh
        with cd(manager_marshal_dir):
            run("./init-submodules.sh")

        with cd(manager_fsim_dir), prefix("source ./sourceme-f1-manager.sh"):
            run(".github/scripts/firesim-managerinit.expect {} {} {}".format(
                os.environ["AWS-ACCESS-KEY-ID"],
                os.environ["AWS-SECRET-ACCESS-KEY"],
                os.environ["AWS-DEFAULT-REGION"]))

        with cd(manager_ci_dir):
            # Put a baseline time-to-live bound on the manager.
            # Instances will be terminated (since they are spot requests) and cleaned up in a nightly job.

            # Setting pty=False is required to stop the screen from being
            # culled when the SSH session associated with the run command ends.
            run("screen -S ttl -dm bash -c \'sleep {}; ./change-workflow-instance-states.py {} terminate {}\'"
                .format(int(max_runtime) * 3600, ci_workflow_run_id, ci_personal_api_token), pty=False)
            run("screen -S workflow-monitor -L -dm ./workflow-monitor.py {} {}"
                .format(ci_workflow_run_id, ci_personal_api_token), pty=False)

    except BaseException as e:
        traceback.print_exc(file=sys.stdout)
        terminate_workflow_instances(ci_personal_api_token, ci_workflow_run_id)
        sys.exit(1)

if __name__ == "__main__":
    max_runtime = sys.argv[1]
    execute(initialize_manager, max_runtime, hosts=["localhost"])
