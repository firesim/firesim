#!/usr/bin/env python3

# Used to launch a fresh manager instance from the CI container.

import sys

# This must run in the CI container
from ci_variables import ci_workflow_run_id, ci_azure_sub_id, ci_azure_resource_group, ci_workdir, \
    ci_commit_sha1, ci_azure_default_region, ci_azure_subnet_id, ci_azure_nsg_id, ci_firesim_pem_public
from common import azure_platform_lib

from azure.mgmt.resource import ResourceManagementClient
from azure.identity import DefaultAzureCredential
from azure.mgmt.network import NetworkManagementClient
from azure.mgmt.compute import ComputeManagementClient

import base64

#get this from ci_variables normally will be github secret
def main():
    """ Spins up a new manager vm for our CI run """

    if azure_platform_lib.check_manager_exists(ci_workflow_run_id):
        print("There is an existing manager vm for this CI workflow:")
        print(azure_platform_lib.get_manager_metadata_string(ci_workflow_run_id))
        sys.exit(0)

    credential = DefaultAzureCredential()

    resource_client = ResourceManagementClient(credential, ci_azure_sub_id)
    rg_result = resource_client.resource_groups.get(ci_azure_resource_group)
    print(f"Provisioned resource group {rg_result.name} in the {rg_result.location} region")

    with open(ci_workdir + "/scripts/machine-launch-script.sh", "r") as file:
        ml_file_raw = file.read().encode('utf-8')
        ml_file_encoded = base64.b64encode(ml_file_raw).decode('latin-1')

    workflow_id = ci_workflow_run_id

    # Netowrking related variables
    ip_name = workflow_id + "-ip"
    ip_config_name = ip_name + "-config"
    nic_name = workflow_id + "-nic"

    # VM-relate
    vm_name = workflow_id + "-vm"
    username = "centos"
    image_name = "xilinx_alveo_u250_deployment_vm_centos78_032321"
    vm_size = "Standard_E8ds_v5" #8 vcpus, 64 gb should be sufficient for CI purposes

    tags = azure_platform_lib.get_manager_tag_dict(ci_commit_sha1, ci_workflow_run_id)

    network_client = NetworkManagementClient(credential, ci_azure_sub_id)
    poller = network_client.public_ip_addresses.begin_create_or_update(ci_azure_resource_group,
        ip_name,
        {
            "location": ci_azure_default_region,
            "tags": tags,
            "sku": {"name": "Standard"},
            "public_ip_allocation_method": "Static",
            "public_ip_address_version" : "IPV4"
        }
    )
    ip_address_result = poller.result()
    print(f"Provisioned public IP address {ip_address_result.name} with address {ip_address_result.ip_address}")
    poller = network_client.network_interfaces.begin_create_or_update(ci_azure_resource_group,
        nic_name,
        {
            "location": ci_azure_default_region,
            "tags": tags,
            "ip_configurations": [ {
                "name": ip_config_name,
                "subnet": { "id": ci_azure_subnet_id },
                "properties" : {
                    "publicIPAddress" : {
                        "id" : ip_address_result.id,
                        "properties" : {
                            "deleteOption" : "Delete" # deletes IP when NIC is deleted
                        }
                    }
                }
            }],
            "networkSecurityGroup": {
                "id": ci_azure_nsg_id
            }
        }
    )
    nic_result = poller.result()
    print(f"Provisioned network interface client {nic_result.name}")

    print(f"Provisioning virtual machine {vm_name}; this operation might take a few minutes.")
    compute_client = ComputeManagementClient(credential, ci_azure_sub_id)
    poller = compute_client.virtual_machines.begin_create_or_update(ci_azure_resource_group, vm_name,
        {
            "location": ci_azure_default_region,
            "tags": tags,
            "plan": {
                "name": image_name,
                "publisher": "xilinx",
                "product": image_name
            },
            "storage_profile": {
                "image_reference": {
                    "publisher": 'xilinx',
                    "offer": image_name,
                    "sku": image_name,
                    "version": "latest"
                },
                "osDisk": {
                    "diskSizeGB": 300,
                    "createOption": "FromImage",
                    "deleteOption": "Delete" # deletes OS Disk when VM is deleted
                }
            },
            "hardware_profile": {
                "vm_size": vm_size
            },
            "os_profile": {
                "computer_name": vm_name,
                "admin_username": username,
                 "linux_configuration": {
                    "disable_password_authentication": True,
                    "ssh": {
                        "public_keys": [{
                            "path": f"/home/{username}/.ssh/authorized_keys",
                            "key_data": ci_firesim_pem_public # use some public key, like firesim.pem, from github secrets
                        }]
                    }
                },
                "custom_data": ml_file_encoded
            },
            "network_profile": {
                "network_interfaces": [{
                    "id": nic_result.id,
                     "properties": { "deleteOption": "Delete" } # deletes NIC when VM is deleted
                }]
            }
        }
    )
    vm_result = poller.result()
    print(f"Provisioned virtual machine {vm_result.name}")

if __name__ == "__main__":
    main()
