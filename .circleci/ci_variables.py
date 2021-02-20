import os
import sys

from common import *

# This file contains utilities that rely on environment variable
# definitions present only on the CI container instance.

# CI instance environment variables
ci_workflow_id = os.environ['CIRCLE_WORKFLOW_ID']
ci_commit_sha1 = os.environ['CIRCLE_SHA1']
# expanduser to replace the ~ present in the default, for portability
ci_workdir     = os.path.expanduser(os.environ['CIRCLE_WORKING_DIRECTORY'])

manager_tag_dict = get_manager_tag_dict(ci_commit_sha1, ci_workflow_id)

def get_manager_instance():
    instances = get_instances_with_filter([get_ci_filter(ci_workflow_id), manager_filter])

    if instances is not None:
        assert len(instances) == 1
        return instances[0]
    else:
        return None

def get_manager_ip():
    manager = get_manager_instance()
    if manager is None:
        print("No manager instance running with tag matching the assigned workflow id\n")
        sys.exit(1)
    else:
        return manager['PublicIpAddress']

def manager_hostname():
    return "centos@{}".format(get_manager_ip())
