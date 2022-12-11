import abc

import sys
import boto3
import os
import pytz
import datetime
from enum import Enum

from azure.mgmt.resource import ResourceManagementClient # type: ignore
from azure.identity import DefaultAzureCredential # type: ignore
from azure.mgmt.compute import ComputeManagementClient # type: ignore
import azure.mgmt.resourcegraph as arg # type: ignore

from ci_variables import ci_env
from github_common import issue_post, get_issue_number

from mypy_boto3_ec2.client import EC2Client
from typing import Any, Callable, Dict, List, Optional, Iterable, Tuple

# Reuse manager utilities
# Note: GITHUB_WORKSPACE must not be used here because the persistent clone my not be initialized yet.
sys.path.append(ci_env['GITHUB_WORKSPACE'] + "/deploy")
from awstools.awstools import get_instances_with_filter

# This tag is common to all instances launched as part of a given workflow
workflow_tag_key = 'ci_workflow_id'
# Managers additionally have this empty tag defined
manager_filter = {'Name': 'tag:ci_manager', 'Values' : ['']}

# The following is an enum used to represent the different platforms
class Platform(Enum):
    ALL = 'all'
    AWS = 'aws'
    AZURE = 'azure'

    def __str__(self):
        return self.value

    def __iter__(self):
        return iter([self.ALL, self.AWS, self.AZURE])

def get_platform_enum(platform_string: str) -> Platform:
    if platform_string == 'aws':
        return Platform.AWS
    elif platform_string == 'azure':
        return Platform.AZURE
    elif platform_string == 'all':
        return Platform.ALL
    else:
        raise Exception(f"Invalid platform string: '{platform_string}'")

def find_timed_out_resources(min_timeout: int, current_time: datetime.datetime, resource_list: Iterable[Tuple]) -> list:
    """
    Because of the differences in how AWS and Azure store time tags, the resource_list
    in this case is a list of tuples with the 0 index being the instance/vm and the 1 index
    a datetime object corresponding to the time
    """
    timed_out = []
    for resource_tuple in resource_list:
        lifetime_secs = (current_time - resource_tuple[1]).total_seconds()
        if lifetime_secs > (min_timeout * 60):
            timed_out.append(resource_tuple[0])
    return timed_out

class PlatformLib(metaclass=abc.ABCMeta):
    """
    This is a class hierarchy to support multiple platforms in FireSim CI
    Note that there are 2 terms used to refer to machines running CI: Managers and Instances

    Managers - refer to a manager instance, this only really matters for AWS, but it's the main machine
    running CI

    Instances - a more general term, but can refer to runfarm / buildfarm instances run by CI as well
    """

    @abc.abstractmethod
    def get_filter(self, workflow_tag: str) -> Dict:
        """ Returns a filter that returns all instances associated with workflow """
        raise NotImplementedError

    @abc.abstractmethod
    def get_manager_tag_dict(self, sha: str, workflow_tag: str) -> Dict[str, str]:
        """ Returns the tag dictionary for launching the manager """
        raise NotImplementedError

    @abc.abstractmethod
    def check_manager_exists(self, workflow_tag: str) -> bool:
        """ Returns whether workflow manager already exists """
        raise NotImplementedError

    @abc.abstractmethod
    def find_all_workflow_instances(self, workflow_tag: str) -> List:
        """ Returns all manager instances in this workflow """
        raise NotImplementedError

    @abc.abstractmethod
    def find_all_ci_instances(self) -> List:
        """ Returns all manager instances across all CI workflows """
        raise NotImplementedError

    @abc.abstractmethod
    def find_run_farm_ci_instances(self, workflow_tag: str = '*') -> List:
        """
        Returns all run farm instance types (normally FPGA instances) that have the
        `workflow_tag` in the cluster name.
        """
        raise NotImplementedError

    @abc.abstractmethod
    def get_manager_ip(self, workflow_tag: str) -> str:
        """ Returns ip of the manager specified by the tag """
        raise NotImplementedError

    @abc.abstractmethod
    def get_manager_workflow_id(self, workflow_tag: str) -> str:
        """ Returns the workflow id of the manager specified by the tag """
        raise NotImplementedError

    @abc.abstractmethod
    def change_workflow_instance_states(self, gh_token: str, workflow_tag: str, state_change: str, dryrun: bool=False) -> None:
        """ Changes the state of the instances specified by 'workflow_tag' to 'state_change' """
        raise NotImplementedError

    @abc.abstractmethod
    def get_platform_enum(self) -> Platform:
        """ Returns the enum associated with the platform implemented by the PlatformLib """
        raise NotImplementedError

    @abc.abstractmethod
    def get_manager_metadata_string(self, workflow_tag: str) -> str:
        """ Get metadata string for the manager of the workflow """
        raise NotImplementedError

    def stop_instances(self, gh_token: str, workflow_tag: str) -> None:
        """ Stops the instances specified by 'workflow_tag' """
        self.change_workflow_instance_states(gh_token, workflow_tag, 'stop')

    def terminate_instances(self, gh_token: str, workflow_tag: str) -> None:
        """ Stops the instances specified by 'workflow_tag' """
        self.change_workflow_instance_states(gh_token, workflow_tag, 'terminate')

    @abc.abstractmethod
    def platform_terminate_instances(self, platform_list: List[Any]) -> None:
        """ Terminates the instances given the platform list """
        raise NotImplementedError

    def get_manager_hostname(self, workflow_tag: str) -> str:
        """ Returns the hostname of the ci manager specified """
        return f"centos@{self.get_manager_ip(workflow_tag)}"

    @abc.abstractmethod
    def check_and_terminate_run_farm_instances(self, timeout: int, workflow_tag: str, issue_id: int) -> None:
        """ Check if run farm instances are running past a `timeout` minutes designated time. If so, then terminate them. """
        raise NotImplementedError


class AWSPlatformLib(PlatformLib):
    client: Optional[EC2Client]

    def __init__(self, deregister_runners: Callable[[str, str], None]):
        if os.path.exists(os.path.expanduser('~/.aws/config')): # only set client if this exists
            self.client = boto3.client('ec2')
        else:
            self.client = None

        self.manager_filter = {'Name': 'tag:ci_manager', 'Values' : ['']}
        self.deregister_runners = deregister_runners

    def get_filter(self, workflow_tag: str) -> Dict[str, Any]:
        return {'Name': 'tag:' + workflow_tag_key, 'Values' : [workflow_tag]}

    def get_manager_tag_dict(self, sha, workflow_tag):
        """ Populates a set of tags for the manager of our CI run """
        # Note: At one point these tags had hyphens instead of underscores.
        # Since hyphens are interpreted as a subtraction operation in
        # Kusto Query Langauge (KQL) used by Azure Resource Graphs,
        # these have been chnaged to underscores as a result.
        return {
            'ci_commit_sha1': sha,
            'ci_manager': '',
            workflow_tag_key: workflow_tag}

    def check_manager_exists(self, workflow_tag: str) -> bool:
        inst = self.find_manager(workflow_tag)
        return not (inst is None)

    def find_manager(self, workflow_tag: str):
        instances = get_instances_with_filter([self.get_filter(workflow_tag), manager_filter], allowed_states = ['pending', 'running', 'stopping', 'stopped'])
        if instances:
            assert len(instances) == 1 # this must be called before any new instances are launched by workflow
            return instances[0]
        else:
            return None

    def find_all_workflow_instances(self, workflow_tag: str) -> List:
        """ Grabs a list of all instance dicts sharing the CI workflow run's unique tag """
        return get_instances_with_filter([self.get_filter(workflow_tag)], allowed_states = ['pending', 'running', 'stopping', 'stopped'])

    def find_all_ci_instances(self) -> List:
        """ Grabs a list of all instances across all CI using the CI unique tag key"""
        all_ci_instances_filter = self.get_filter('*')
        all_ci_instances = get_instances_with_filter([all_ci_instances_filter], allowed_states = ['pending', 'running', 'stopping', 'stopped'])
        return all_ci_instances

    def find_run_farm_ci_instances(self, workflow_tag: str = '*') -> List:
        # on AWS run farm instances are marked with 'fsimcluster'
        instances_filter = [
                {'Name': 'tag:fsimcluster', 'Values': [f'*{workflow_tag}*']},
                {'Name': 'instance-type', 'Values': ['f1.2xlarge', 'f1.4xlarge', 'f1.16xlarge']},
                ]
        ci_instances = get_instances_with_filter(instances_filter, allowed_states = ['pending', 'running', 'stopping', 'stopped'])
        return ci_instances

    def get_manager_ip(self, workflow_tag: str) -> str:
        """
        Looks up the AWS manager IP using the CI workflow run's unique tag
        """
        aws_manager = self.find_manager(workflow_tag)
        if aws_manager is None:
            raise Exception("No AWS manager instance running with tag matching the assigned workflow id\n")

        return aws_manager['PublicIpAddress']

    def get_manager_workflow_id(self, workflow_tag: str) -> str:
        return f"aws-{workflow_tag}"

    def change_workflow_instance_states(self, gh_token: str, workflow_tag: str, state_change: str, dryrun: bool = False) -> None:
        """ Change the state of all instances sharing the same CI workflow run's tag. """

        # We need this in case terminate is called in setup-self-hosted-workflow before aws-configure is run
        if self.client is None:
            self.client = boto3.client('ec2')

        all_instances = self.find_all_workflow_instances(workflow_tag)
        manager_instance = self.find_manager(workflow_tag)

        # Ensure we do the manager last, as this function may be invoked there
        sorted_instances = [inst for inst in all_instances if inst != manager_instance]
        if manager_instance is not None:
            sorted_instances.append(manager_instance)

        instance_ids = [inst['InstanceId'] for inst in sorted_instances]

        if state_change == 'stop':
            print(f"Stopping instances: {', '.join(instance_ids)}")
            self.deregister_runners(gh_token, f"aws-{workflow_tag}")
            self.client.stop_instances(InstanceIds=instance_ids, DryRun=dryrun)
        elif state_change == 'start':
            print(f"Starting instances: {', '.join(instance_ids)}")
            self.client.start_instances(InstanceIds=instance_ids, DryRun=dryrun)

            # If we have a manager (typical), wait for it to come up and report its IP address
            if manager_instance is not None:
                print("Waiting on manager instance.")
                manager_id = manager_instance['InstanceId']
                #wait_on_instance(manager_id) # TODO: this is not defined anywhere, even in the OG common.py
                print("Manager ready.")
                # Re-query the instance to get an updated IP address
                print(self.instance_metadata_str(self.find_manager(workflow_tag)))

        elif state_change == 'terminate':
            print(f"Terminating instances: {', '.join(instance_ids)}")
            self.deregister_runners(gh_token, f"aws-{workflow_tag}")
            self.platform_terminate_instances(instance_ids, dryrun)
        else:
            raise ValueError(f"Unrecognized transition type: {state_change}")

    def platform_terminate_instances(self, platform_list: List[Any], dryrun: bool = False) -> None:
        # We need this in case terminate is called in setup-self-hosted-workflow before aws-configure is run
        if self.client is None:
            self.client = boto3.client('ec2')

        self.client.terminate_instances(InstanceIds=platform_list, DryRun=dryrun)

    def get_platform_enum(self) -> Platform:
        return Platform.AWS

    def get_manager_metadata_string(self, workflow_tag: str) -> str:
        return self.instance_metadata_str(self.find_manager(workflow_tag))

    def instance_metadata_str(self, instance) -> str:
        """ Pretty prints instance info, including ID, state, and public IP """
        static_md = f"""    Instance ID: {instance['InstanceId']}
        Instance State: {instance['State']['Name']}"""

        dynamic_md = ""

        if instance.get('PublicIpAddress') is not None:
            dynamic_md = f"""
        Instance IP: {instance['PublicIpAddress']}"""

        return static_md + dynamic_md

    def check_and_terminate_run_farm_instances(self, timeout: int, workflow_tag: str, issue_id: int) -> None:
        # We need this in case terminate is called in setup-self-hosted-workflow before aws-configure is run
        if self.client is None:
            self.client = boto3.client('ec2')

        instances = self.find_run_farm_ci_instances(workflow_tag)
        instances_to_terminate = find_timed_out_resources(
                timeout,
                datetime.datetime.utcnow().replace(tzinfo=pytz.UTC),
                map(lambda x: (x, x['LaunchTime']), instances))

        for inst in instances_to_terminate:
            print(f"Uncaught run farm instance shutdown detected for inst: {inst}")
            self.client.terminate_instances(InstanceIds=[inst['InstanceId']])
            print(f"Terminated run farm instance {inst['InstanceId']}")

        # post comment after instances are terminated just in case there is an issue with posting
        if len(instances_to_terminate) > 0:
            issue_post(ci_env['PERSONAL_ACCESS_TOKEN'], issue_id,
                    f"Uncaught {len(instances_to_terminate)} FPGA instance shutdown(s) detected for CI run: {ci_env['GITHUB_RUN_ID']}. Verify CI state before submitting PR.")


class AzurePlatformLib(PlatformLib):
    def __init__(self, deregister_runners: Callable[[str, str], None]):
        self.credential = DefaultAzureCredential()
        self.resource_client = ResourceManagementClient(self.credential, ci_env['AZURE_SUBSCRIPTION_ID'])
        self.arg_client = arg.ResourceGraphClient(self.credential)
        self.compute_client = ComputeManagementClient(self.credential, ci_env['AZURE_SUBSCRIPTION_ID'])

        self.deregister_runners = deregister_runners
        # This is a dictionary that's used to translate between simpler terms and
        # those useful to search for Azure resources
        self.azure_translation_dict = {
            'vm' : 'virtualMachines',
            'ip' : 'publicIPAddresses',
            'nsg' : 'networkSecurityGroups',
            'nic' : 'networkInterfaces',
            'disk' : 'disks',
            'vnet' : 'virtualNetworks'
        }

    def get_filter(self, workflow_tag: str) -> Dict[str, str]:
        return {workflow_tag_key: workflow_tag}

    def get_manager_tag_dict(self, sha, workflow_tag):
        """ Populates a set of tags for the manager of our CI run """
        # Note: At one point these tags had hyphens instead of underscores.
        # Since hyphens are interpreted as a subtraction operation in
        # Kusto Query Langauge (KQL) used by Azure Resource Graphs,
        # these have been chnaged to underscores as a result.
        return {
            'ci_commit_sha1': sha,
            'ci_manager': '',
            workflow_tag_key: workflow_tag,
            'FireSimCI': 'True',
            'LaunchTime': datetime.datetime.utcnow().replace(tzinfo=pytz.UTC)}

    def check_manager_exists(self, workflow_tag: str):
        # Note: Right now, Azure workflow does not spawn new instances
        return len(self.find_all_workflow_instances(workflow_tag)) == 1

    def find_all_workflow_instances(self, workflow_tag : str) -> List:
        tag_filter = self.get_filter(workflow_tag)
        all_ci_resources = self.get_azure_resources_with_tags(tag_filter)
        return self.get_type_from_resource_list(all_ci_resources, 'vm')

    def find_all_ci_instances(self) -> List:
        tag_filter = {'FireSimCI' : "True"}
        all_ci_resources = self.get_azure_resources_with_tags(tag_filter)
        return self.get_type_from_resource_list(all_ci_resources, 'vm')

    def get_manager_ip(self, workflow_tag : str) -> str:
        """
        Looks up the Azure manager IP using the CI workflow run's unique tag
        """
        azure_manager_resources = self.get_azure_resources_with_tags(self.get_filter(workflow_tag))
        azure_ip = self.get_type_from_resource_list(azure_manager_resources, 'ip')

        if not azure_ip: #if an empty list is returned
            raise Exception("No Azure IP found associated with tag matching the assigned workflow id\n")

        azure_ip = azure_ip[0] #assume only 1 ip in list
        return azure_ip['properties']['ipAddress']

    def get_manager_workflow_id(self, workflow_tag: str) -> str:
        return f"azure-{workflow_tag}"

    def change_workflow_instance_states(self, gh_token: str, workflow_tag: str, state_change: str, dryrun: bool=False) -> None:
        """
            Now that terminate / stop look a bit more similar, we can condense them into a single function
            Dryrun is just a dummy variable to match abstract class signature
        """
        instances = self.find_all_workflow_instances(workflow_tag)

        if not instances: #if an empty list is returned
            raise Exception(f"Couldn't find an active vm associated with tags {self.get_filter(workflow_tag)}")

        if state_change == 'stop':
            self.deregister_runners(gh_token, self.get_manager_workflow_id(workflow_tag))
            for inst in instances:
                print(f"Flagged VM {inst['name']} for shutdown")
                poller = self.compute_client.virtual_machines.begin_power_off(inst['resourceGroup'], inst['name'])
                print(f"Successfully stopped VM {inst['name']}")
        elif state_change == 'terminate':
            self.deregister_runners(gh_token, self.get_manager_workflow_id(workflow_tag))
            self.terminate_azure_vms(instances)
        elif state_change == 'start':
            raise  NotImplementedError
        else:
            raise ValueError(f"Unrecognized transition type: {state_change}")

    def get_platform_enum(self) -> Platform:
        return Platform.AZURE

    def get_manager_metadata_string(self, workflow_tag: str) -> str:
        inst_list = self.find_all_workflow_instances(workflow_tag)
        assert len(inst_list) == 1
        manager = inst_list[0]
        return str(manager)

    def get_azure_type_key(self, type_name: str) -> str:
        return self.azure_translation_dict[type_name]

    def get_type_from_resource_list(self, resource_list: List, type_name: str):
        """
            Gets specific type of resource from a resource list obtained from one of the query
        """
        type_key = self.get_azure_type_key(type_name)
        return_list = []
        for resource in resource_list:
            if type_key.casefold() in resource['type'].casefold():
                return_list.append(resource)

        return return_list

    def get_azure_resources_with_tags(self, tag_dict: Dict[str, str]) -> List:
        arg_query_options = arg.models.QueryRequestOptions(result_format="objectArray")

        query = "Resources | where "
        for key in tag_dict.keys():
            query += f"tags.{key}=~'{tag_dict[key]}' and "
        query = query[:-4]

        arg_query = arg.models.QueryRequest(subscriptions=[ci_env['AZURE_SUBSCRIPTION_ID']], query=query, options=arg_query_options)

        return self.arg_client.resources(arg_query).data

    def platform_terminate_instances(self, platform_list: List[Any], dryrun: bool = False) -> None:
        self.terminate_azure_vms(platform_list)

    def terminate_azure_vms(self, resource_list: List) -> None:
        vms_to_delete = []
        for resource in resource_list:
            if 'virtualMachines'.casefold() in resource['type'].casefold():
                vms_to_delete.append(resource)

        vm_pollers = []
        for vm in vms_to_delete:
            poller = self.resource_client.resources.begin_delete_by_id(vm['id'], self.resource_client.DEFAULT_API_VERSION)
            print(f"VM {vm['name']} flagged for deletion")
            vm_pollers.append((vm, poller))

        for vm, poller in vm_pollers:
            deletion_result = poller.result()
            if deletion_result:
                print(f"Failed to delete VM {vm['name']}, expected 'None' and got result {deletion_result}")
            else:
                print(f"Succeeded in deleting VM {vm['name']}")

    def check_and_terminate_run_farm_instances(self, timeout: int, workflow_tag: str, issue_id: int) -> None:
        raise NotImplementedError

    def find_run_farm_ci_instances(self, workflow_tag: str = '*') -> List:
        raise NotImplementedError
