from fabric.api import env # type: ignore
import requests

from typing import Dict

from platform_lib import PlatformLib, AWSPlatformLib, AzurePlatformLib, Platform
from ci_variables import ci_env
from github_common import deregister_runners

# Remote paths
manager_home_dir = "/home/centos"
manager_fsim_pem = manager_home_dir + "/firesim.pem"
manager_fsim_dir = ci_env['MANAGER_FIRESIM_LOCATION']
manager_marshal_dir = manager_fsim_dir + "/sw/firesim-software"
manager_ci_dir = manager_fsim_dir + "/.github/scripts"

# Common fabric settings
env.output_prefix = False
env.abort_on_prompts = True
env.timeout = 100
env.connection_attempts = 10
env.disable_known_hosts = True
env.keepalive = 60 # keep long SSH connections running

def set_fabric_firesim_pem() -> None:
    env.key_filename = manager_fsim_pem

aws_platform_lib = AWSPlatformLib(deregister_runners)
#azure_platform_lib = AzurePlatformLib(deregister_runners)

def get_platform_lib(platform: Platform) -> PlatformLib:
    if platform == Platform.AWS:
        return aws_platform_lib
    elif platform == Platform.AZURE:
        #return azure_platform_lib
        raise Exception(f"Azure not yet supported")
    else:
        raise Exception(f"Invalid platform: '{platform}'")
