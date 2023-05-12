import os
from typing import TypedDict

# This package contains utilities that rely on environment variable
# definitions present only on the CI container instance.

# environment variables needed by CI
class CIEnvironment(TypedDict):
    # If not running under a CI pipeline defaults are provided that
    # will suffice to run scripts that do not use GHA API calls.
    # To manually provide environment variable settings, export GITHUB_ACTIONS=true, and provide
    # values for all of the environment variables listed.
    GITHUB_ACTIONS: str

    # This is used as a unique tag for all instances launched in a workflow
    GITHUB_RUN_ID: str

    GITHUB_SHA: str

    # Multiple clones of the FireSim repository exists on manager. We expect state
    # to persist between jobs in a workflow and faciliate that by having jobs run
    # out of a centralized clone (MANAGER_FIRESIM_LOCATION)-- not the default clones setup by
    # the GHA runners (GITHUB_WORKSPACE)

    # This is the location of the clone setup by the GHA runner infrastructure by default
    # expanduser to replace the ~ present in the default, for portability
    GITHUB_WORKSPACE: str

    # This is the location of the reused clone. CI scripts should refer variables
    # derived from this path so that they may be reused across workflows that may
    # initialize the FireSim repository differently (e.g., as a submodule of a
    # larger project.)
    MANAGER_FIRESIM_LOCATION: str

    GITHUB_TOKEN: str
    PERSONAL_ACCESS_TOKEN: str
    GITHUB_API_URL: str

    # We look this up, instead of hardcoding "firesim/firesim", to support running
    # this CI pipeline under forks.
    GITHUB_REPOSITORY: str

    GITHUB_EVENT_PATH: str

    # The following are environment variables used by AWS and AZURE to setup the corresponding
    # self-hosted Github Actions Runners
    AWS_ACCESS_KEY_ID: str
    AWS_SECRET_ACCESS_KEY: str
    AWS_DEFAULT_REGION: str
    AZURE_CLIENT_ID: str
    AZURE_CLIENT_SECRET: str
    AZURE_TENANT_ID: str
    AZURE_SUBSCRIPTION_ID: str
    AZURE_DEFAULT_REGION: str
    AZURE_RESOURCE_GROUP: str
    AZURE_CI_SUBNET_ID: str
    AZURE_CI_NSG_ID: str

    FIRESIM_PEM: str
    FIRESIM_PEM_PUBLIC: str

RUN_LOCAL = os.environ.get('GITHUB_ACTIONS', 'false') == 'false'
# When running locally (not in a CI pipeline) run commands out of the clone hosting this file.
local_fsim_dir = os.path.normpath((os.path.realpath(__file__)) + "/../../..")

def get_ci_value(env_var: str, default_value: str = "") -> str:
    if RUN_LOCAL:
        return default_value
    else:
        return os.environ[env_var]

# Create a env. dict that is populated from the environment or from defaults
ci_env: CIEnvironment = {
    'GITHUB_ACTIONS': 'false' if RUN_LOCAL else 'true',
    'GITHUB_RUN_ID': get_ci_value('GITHUB_RUN_ID'),
    'GITHUB_SHA': get_ci_value('GITHUB_RUN_ID'),
    'GITHUB_WORKSPACE': os.path.expanduser(os.environ['GITHUB_WORKSPACE']) if not RUN_LOCAL else local_fsim_dir,
    'MANAGER_FIRESIM_LOCATION': os.path.expanduser(os.environ['MANAGER_FIRESIM_LOCATION']) if not RUN_LOCAL else local_fsim_dir,
    'GITHUB_TOKEN': get_ci_value('GITHUB_TOKEN'),
    'PERSONAL_ACCESS_TOKEN': get_ci_value('PERSONAL_ACCESS_TOKEN'),
    'GITHUB_API_URL': get_ci_value('GITHUB_API_URL'),
    'GITHUB_REPOSITORY': get_ci_value('GITHUB_REPOSITORY'),
    'GITHUB_EVENT_PATH': get_ci_value('GITHUB_EVENT_PATH'),
    'AWS_ACCESS_KEY_ID': get_ci_value('AWS_ACCESS_KEY_ID'),
    'AWS_SECRET_ACCESS_KEY': get_ci_value('AWS_SECRET_ACCESS_KEY'),
    'AWS_DEFAULT_REGION': get_ci_value('AWS_DEFAULT_REGION'),
    'AZURE_CLIENT_ID': get_ci_value('AZURE_CLIENT_ID'),
    'AZURE_CLIENT_SECRET': get_ci_value('AZURE_CLIENT_SECRET'),
    'AZURE_TENANT_ID': get_ci_value('AZURE_TENANT_ID'),
    'AZURE_SUBSCRIPTION_ID': get_ci_value('AZURE_SUBSCRIPTION_ID'),
    'AZURE_DEFAULT_REGION': get_ci_value('AZURE_DEFAULT_REGION'),
    'AZURE_RESOURCE_GROUP': get_ci_value('AZURE_RESOURCE_GROUP'),
    'AZURE_CI_SUBNET_ID': get_ci_value('AZURE_CI_SUBNET_ID'),
    'AZURE_CI_NSG_ID': get_ci_value('AZURE_CI_NSG_ID'),
    'FIRESIM_PEM': get_ci_value('FIRESIM_PEM'),
    'FIRESIM_PEM_PUBLIC': get_ci_value('FIRESIM_PEM_PUBLIC'),
}
