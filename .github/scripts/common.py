import math
from fabric.api import *
import requests
from ci_variables import ci_gha_api_url, ci_repo_name, ci_firesim_dir

from typing import Dict, List, Any

from platform_lib import PlatformLib, AWSPlatformLib, AzurePlatformLib, Platform

# Github URL related constants
gha_api_url         = f"{ci_gha_api_url}/repos/{ci_repo_name}/actions"
gha_runners_api_url = f"{gha_api_url}/runners"
gha_runs_api_url    = f"{gha_api_url}/runs"

# Remote paths
manager_home_dir = "/home/centos"
manager_fsim_pem = manager_home_dir + "/firesim.pem"
manager_fsim_dir = ci_firesim_dir
manager_marshal_dir = manager_fsim_dir + "/sw/firesim-software"
manager_ci_dir = manager_fsim_dir + "/.github/scripts"

# Common fabric settings
env.output_prefix = False
env.abort_on_prompts = True
env.timeout = 100
env.connection_attempts = 10
env.disable_known_hosts = True
env.keepalive = 60 # keep long SSH connections running

def set_fabric_firesim_pem():
    env.key_filename = manager_fsim_pem

def get_header(gh_token: str) -> Dict[str, str]:
    return {"Authorization": f"token {gh_token.strip()}", "Accept": "application/vnd.github+json"}

def get_runners(gh_token: str) -> List:
    r = requests.get(gha_runners_api_url, headers=get_header(gh_token))
    if r.status_code != 200:
        raise Exception(f"Unable to retrieve count of GitHub Actions Runners\nFull Response Below:\n{r}")
    res_dict = r.json()
    runner_count = res_dict["total_count"]

    runners = []
    for page_idx in range(math.ceil(runner_count / 30)):
        r = requests.get(gha_runners_api_url, params={"per_page" : 30, "page" : page_idx + 1}, headers=get_header(gh_token))
        if r.status_code != 200:
            raise Exception(f"Unable to retrieve (sub)list of GitHub Actions Runners\nFull Response Below\n{r}")
        res_dict = r.json()
        runners = runners + res_dict["runners"]

    return runners

def delete_runner(gh_token: str, runner: Dict[str, Any]) -> bool:
    r = requests.delete(f"""{gha_runners_api_url}/{runner["id"]}""", headers=get_header(gh_token))
    if r.status_code != 204:
        print(f"""Unable to delete runner {runner["name"]} with id: {runner["id"]}\nFull Response Below\n{r}""")
        return False
    return True

def deregister_offline_runners(gh_token: str) -> None:
    runners = get_runners(gh_token)
    for runner in runners:
        if runner["status"] == "offline":
            delete_runner(gh_token, runner)

def deregister_runners(gh_token: str, runner_name: str) -> None:
    runners = get_runners(gh_token)
    for runner in runners:
        if runner_name in runner["name"]:
            delete_runner(gh_token, runner)

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
