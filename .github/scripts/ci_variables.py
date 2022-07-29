import os

# This package contains utilities that rely on environment variable
# definitions present only on the CI container instance.

# If not running under a CI pipeline defaults are provided that
# will suffice to run scripts that do not use GHA API calls.
# To manually provide environment variable settings, export GITHUB_ACTIONS=true, and provide
# values for all of the environment variables below.
RUN_LOCAL = not os.environ.get('GITHUB_ACTIONS', False)
# When running locally (not in a CI pipeline) run commands out of the clone hosting this file.
local_fsim_dir = os.path.normpath((os.path.realpath(__file__)) + "/../../..")

# CI instance environment variables

# This is used as a unique tag for all instances launched in a workflow
ci_workflow_run_id = os.environ['GITHUB_RUN_ID'] if not RUN_LOCAL else 0
ci_commit_sha1 = os.environ['GITHUB_SHA'] if not RUN_LOCAL else 0

# Multiple clones of the FireSim repository exists on manager. We expect state
# to persist between jobs in a workflow and faciliate that by having jobs run
# out of a centralized clone (ci_firesim_dir)-- not the default clones setup by
# the GHA runners (ci_workdir)

# This is the location of the clone setup by the GHA runner infrastructure by default
# expanduser to replace the ~ present in the default, for portability
ci_workdir = os.path.expanduser(os.environ['GITHUB_WORKSPACE']) if not RUN_LOCAL else local_fsim_dir

# This is the location of the reused clone. CI scripts should refer variables
# derived from this path so that they may be reused across workflows that may
# initialize the FireSim repository differently (e.g., as a submodule of a
# larger project.)
ci_firesim_dir = os.path.expanduser(os.environ['MANAGER_FIRESIM_LOCATION']) if not RUN_LOCAL else local_fsim_dir

ci_api_token = os.environ['GITHUB_TOKEN'] if not RUN_LOCAL else 0
ci_personal_api_token = os.environ['PERSONAL_ACCESS_TOKEN'] if not RUN_LOCAL else 0

ci_gha_api_url = os.environ['GITHUB_API_URL'] if not RUN_LOCAL else ""
# We look this up, instead of hardcoding "firesim/firesim", to support running
# this CI pipeline under forks.
ci_repo_name   = os.environ['GITHUB_REPOSITORY'] if not RUN_LOCAL else ""