import os
from local_flags import RUN_LOCAL, local_fsim_dir

# This package contains utilities that rely on environment variable
# definitions present only on the CI container instance.

# CI instance environment variables
# This is used as a unique tag for all instances launched in a workflow
ci_workflow_run_id = os.environ['GITHUB_RUN_ID'] if not RUN_LOCAL else 0
ci_commit_sha1 = os.environ['GITHUB_SHA'] if not RUN_LOCAL else 0
# expanduser to replace the ~ present in the default, for portability
ci_workdir = os.path.expanduser(os.environ['GITHUB_WORKSPACE']) if not RUN_LOCAL else local_fsim_dir
ci_api_token = os.environ['GITHUB_TOKEN'] if not RUN_LOCAL else 0
ci_personal_api_token = os.environ['PERSONAL_ACCESS_TOKEN'] if not RUN_LOCAL else 0
