import os

# This package contains utilities that rely on environment variable
# definitions present only on the CI container instance.

# CI instance environment variables
# This is used as a unique tag for all instances launched in a workflow
ci_workflow_run_id = os.environ['GITHUB_RUN_ID']
ci_commit_sha1 = os.environ['GITHUB_SHA']
# expanduser to replace the ~ present in the default, for portability
ci_workdir = os.path.expanduser(os.environ['GITHUB_WORKSPACE'])
ci_api_token = os.environ['GITHUB_TOKEN']
ci_personal_api_token = os.environ['PERSONAL_ACCESS_TOKEN']
