#!/usr/bin/env python

from fabric.api import execute

from common import *
# This is expected to be launch from the ci container
from ci_variables import *

def initialize_manager(max_runtime, filesystem_timeout):
    """ Preforms the prerequisite tasks for all CI jobs that will run on the manager instance

    max_runtime (seconds): The maximum uptime this manager should have be for it is terminated
        This covers potential livelock scenario in one of the jobs (that produces fs activity)

    filesystem_timeout (seconds):  If no fs activity is detected in ~/firesim after 2x this
        interval, the manager is terminated.  CircleCI provides no mechanism for
        doing cleanup after all jobs are done (even if some of those jobs fail).
        So here we use FS activity as a proxy for doneness
    """

    # Catch any exception that occurs so that we can gracefully teardown the local jjjjjjjjjjjjjjjjjjjjjjj
    try:
        put(ci_workdir + "/scripts/machine-launch-script.sh", manager_home_dir)
        with cd(manager_home_dir):
            put(ci_workdir + "/scripts/machine-launch-script.sh", ".")
            run("chmod +x machine-launch-script.sh")
            run("sudo ./machine-launch-script.sh")
            run("git clone https://github.com/davidbiancolin/firesim.git")

        with cd(manager_fsim_dir):
            run("git checkout " + ci_commit_sha1)
            run("./build-setup.sh --fast")

        with cd(manager_fsim_dir), prefix("source ./sourceme-f1-manager.sh"):
            run(".circleci/firesim-managerinit.expect {} {} {}".format(
                os.environ["AWS_ACCESS_KEY_ID"],
                os.environ["AWS_SECRET_ACCESS_KEY"],
                os.environ["AWS_DEFAULT_REGION"]))

        with cd(manager_ci_dir):
            # Set up a pair of crude checks to powerdown the instance
            # The sleep 1 is required for the screen command to not be DOA
            run("screen -d -m ./manager-watchdog.sh {} {} {}; sleep 1".format(max_runtime, filesystem_timeout, ci_workflow_id))
    except BaseException as e:
        print(e)
        local("{}/.circleci/terminate-workflow-instances.py {}".format(ci_workdir, ci_workflow_id))
        sys.exit(1)

if __name__ == "__main__":
    max_runtime = sys.argv[1]
    filesystem_timeout = sys.argv[2]
    execute(initialize_manager, max_runtime, filesystem_timeout, hosts = [manager_hostname()])
