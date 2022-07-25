import os
from local_flags import RUN_LOCAL, local_fsim_dir

# This package contains utilities that rely on environment variable
# definitions present only on the CI container instance.

# CI instance environment variables
# This is used as a unique tag for all instances launched in a workflow
ci_workflow_run_id = os.environ['GITHUB_RUN_ID'] if not RUN_LOCAL else 0
ci_commit_sha1 = os.environ['GITHUB_SHA'] if not RUN_LOCAL else 0

# Multiple clones of the FireSim repository exists on manager. We expect state
# to persist between jobs in a workflow and faciliate that by having jobs run
# out of a centralized clone -- not the default ones setup by the GHA runners by
# default

# This is the location of the clone setup by the GHA runner infrastructure by default
# expanduser to replace the ~ present in the default, for portability
ci_workdir = os.path.expanduser(os.environ['GITHUB_WORKSPACE']) if not RUN_LOCAL else local_fsim_dir

# This is the location of the reused clone. It can by overriden to allow
# reusing steps in a workflow that setups FireSim as a submodule. 
ci_firesim_dir = os.path.expanduser(os.environ['MANAGER_FIRESIM_LOCATION']) if not RUN_LOCAL else local_fsim_dir

ci_api_token = os.environ['GITHUB_TOKEN'] if not RUN_LOCAL else 0
ci_personal_api_token = os.environ['PERSONAL_ACCESS_TOKEN'] if not RUN_LOCAL else 0
