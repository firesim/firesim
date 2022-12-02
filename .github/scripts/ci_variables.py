import os

# This package contains utilities that rely on environment variable
# definitions present only on the CI container instance.

# Create a env. dict that is populated from the environment or from defaults
ci_env = {}

# If not running under a CI pipeline defaults are provided that
# will suffice to run scripts that do not use GHA API calls.
# To manually provide environment variable settings, export GITHUB_ACTIONS=true, and provide
# values for all of the environment variables below.
ci_env['GITHUB_ACTIONS'] = os.environ.get('GITHUB_ACTIONS', "false")
RUN_LOCAL = ci_env['GITHUB_ACTIONS'] == 'false'
# When running locally (not in a CI pipeline) run commands out of the clone hosting this file.
local_fsim_dir = os.path.normpath((os.path.realpath(__file__)) + "/../../..")

# Add list of env. variables to the ci_env dict
def add_env_vars(env_vars, default_value = ""):
    for k, v in os.environ.items():
        if k in env_vars:
            ci_env[k] = v if not RUN_LOCAL else default_value

# CI instance environment variables

gh_env_vars = {
    # This is used as a unique tag for all instances launched in a workflow
    'GITHUB_RUN_ID',
    'GITHUB_SHA',
    }
add_env_vars(gh_env_vars, 0)

# Multiple clones of the FireSim repository exists on manager. We expect state
# to persist between jobs in a workflow and faciliate that by having jobs run
# out of a centralized clone (MANAGER_FIRESIM_LOCATION)-- not the default clones setup by
# the GHA runners (GITHUB_WORKSPACE)

# This is the location of the clone setup by the GHA runner infrastructure by default
# expanduser to replace the ~ present in the default, for portability
ci_env['GITHUB_WORKSPACE'] = os.path.expanduser(os.environ['GITHUB_WORKSPACE']) if not RUN_LOCAL else local_fsim_dir

# This is the location of the reused clone. CI scripts should refer variables
# derived from this path so that they may be reused across workflows that may
# initialize the FireSim repository differently (e.g., as a submodule of a
# larger project.)
ci_env['MANAGER_FIRESIM_LOCATION'] = os.path.expanduser(os.environ['MANAGER_FIRESIM_LOCATION']) if not RUN_LOCAL else local_fsim_dir

gh_env_vars = {
    'GITHUB_TOKEN',
    'PERSONAL_ACCESS_TOKEN',
    }
add_env_vars(gh_env_vars, 0)

gh_env_vars = {
    'GITHUB_API_URL',
    # We look this up, instead of hardcoding "firesim/firesim", to support running
    # this CI pipeline under forks.
    'GITHUB_REPOSITORY',
    'GITHUB_EVENT_PATH',
    }
add_env_vars(gh_env_vars)

# The following are environment variables used by AWS and AZURE to setup the corresponding
# self-hosted Github Actions Runners

aws_env_vars = {
    'AWS_ACCESS_KEY_ID',
    'AWS_SECRET_ACCESS_KEY',
    'AWS_DEFAULT_REGION',
    }
add_env_vars(aws_env_vars)

azure_env_vars = {
    'AZURE_CLIENT_ID',
    'AZURE_CLIENT_SECRET',
    'AZURE_TENANT_ID',
    'AZURE_SUBSCRIPTION_ID',
    'AZURE_DEFAULT_REGION',
    'AZURE_RESOURCE_GROUP',
    'AZURE_CI_SUBNET_ID',
    'AZURE_CI_NSG_ID',
    }
add_env_vars(azure_env_vars)

pem_env_vars = {
    'FIRESIM_PEM',
    'FIRESIM_PEM_PUBLIC',
    }
add_env_vars(pem_env_vars)
