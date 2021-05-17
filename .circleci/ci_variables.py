import os

# This package contains utilities that rely on environment variable
# definitions present only on the CI container instance.

# CI instance environment variables
# This is used as a unique tag for all instances launched in a workflow
ci_workflow_id = os.environ['CIRCLE_WORKFLOW_ID']
ci_commit_sha1 = os.environ['CIRCLE_SHA1']
# expanduser to replace the ~ present in the default, for portability
ci_workdir = os.path.expanduser(os.environ['CIRCLE_WORKING_DIRECTORY'])
ci_api_token = os.environ['CIRCLE_CI_API_TOKEN']

