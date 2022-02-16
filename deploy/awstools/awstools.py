#!/usr/bin/env python3

from __future__ import print_function

import random
import logging
import os

from datetime import datetime, timedelta
import time
import sys

import boto3
import botocore
from botocore import exceptions
from fabric.api import local, hide, settings

# setup basic config for logging
if __name__ == '__main__':
    logging.basicConfig()

rootLogger = logging.getLogger()

# this needs to be updated whenever the FPGA Dev AMI changes
# You can find this by going to the AMI tab under EC2 and searching for public images:
# https://console.aws.amazon.com/ec2/v2/home?region=us-east-1#Images:visibility=public-images;search=FPGA%20Developer;sort=name
# And whenever this changes, you also need to update deploy/tests/test_amis_snake.json
f1_ami_name = "FPGA Developer AMI - 1.11.0-40257ab5-6688-4c95-97d1-e251a40fd1fc"

def aws_resource_names():
    """ Get names for various aws resources the manager relies on. For example:
    vpcname, securitygroupname, keyname, etc.

    Regular users are instructed to hardcode many of these to firesim.

    Other users may have special settings pre-determined for them (e.g.
    tutorial users. This function produces the correct dict by looking up
    tags applied to the manager instance.

    Returns dict with at least:
        'vpcname', 'securitygroupname', 'keyname', 's3bucketname', 'snsname',
        'runfarmprefix'.

    Note that these tags are NOT used to enforce the usage of these resources,
    rather just to configure the manager. Enforcement is done in IAM
    policies where necessary."""

    base_dict = {
        'tutorial_mode'  :   False,
        # regular users are instructed to create these in the setup instructions
        'vpcname':           'firesim',
        'securitygroupname': 'firesim',
        # regular users are instructed to create a key named `firesim` in the wiki
        'keyname':           'firesim',
        's3bucketname' :     None,
        'snsname'      :     'FireSim',
        'runfarmprefix':     None,
    }

    resp = None
    res = None
    # This takes multiple minutes without a timeout from the CI container. In
    # practise it should resolve nearly instantly on an initialized EC2 instance.
    curl_connection_timeout = 10
    with settings(warn_only=True), hide('everything'):
        res = local("""curl -s --connect-timeout {} http://169.254.169.254/latest/meta-data/instance-id""".format(curl_connection_timeout), capture=True)

    # Use the default dictionary if we're not on an EC2 instance (e.g., when a
    # manager is launched from CI; during demos)
    if res.return_code != 0:
        return base_dict


    # Look up this instance's ID, if we do not have permission to describe tags, use the default dictionary
    client = boto3.client('ec2')
    try:
        instanceid = res.stdout
        rootLogger.debug(instanceid)

        resp = client.describe_tags(
            Filters=[
                {
                    'Name': 'resource-id',
                    'Values': [
                        instanceid,
                    ]
                },
            ]
        )
    except client.exceptions.ClientError:
        return base_dict

    resptags = {}
    for pair in resp['Tags']:
        resptags[pair['Key']] = pair['Value']
    rootLogger.debug(resptags)

    in_tutorial_mode = 'firesim-tutorial-username' in resptags.keys()
    if not in_tutorial_mode:
        return base_dict

    # at this point, assume we are in tutorial mode and get all tags we need
    base_dict['tutorial_mode']     = True
    base_dict['vpcname']           = resptags['firesim-tutorial-username']
    base_dict['securitygroupname'] = resptags['firesim-tutorial-username']
    base_dict['keyname']           = resptags['firesim-tutorial-username']
    base_dict['s3bucketname']      = resptags['firesim-tutorial-username']
    base_dict['snsname']           = resptags['firesim-tutorial-username']
    base_dict['runfarmprefix']     = resptags['firesim-tutorial-username']

    return base_dict

# AMIs are region specific
def get_f1_ami_id():
    """ Get the AWS F1 Developer AMI by looking up the image name -- should be region independent.
    """
    client = boto3.client('ec2')
    response = client.describe_images(Filters=[{'Name': 'name', 'Values': [f1_ami_name]}])
    assert len(response['Images']) == 1
    return response['Images'][0]['ImageId']

def get_aws_userid():
    """ Get the user's IAM ID to intelligently create a bucket name when doing managerinit.
    The previous method to do this was:

    client = boto3.client('iam')
    return client.get_user()['User']['UserId'].lower()

    But it seems that by default many accounts do not have permission to run this,
    so instead we get it from instance metadata.
    """
    res = local("""curl -s http://169.254.169.254/latest/dynamic/instance-identity/document | grep -oP '(?<="accountId" : ")[^"]*(?=")'""", capture=True)
    return res.stdout.lower()

def construct_instance_market_options(instancemarket, spotinterruptionbehavior, spotmaxprice):
    """ construct the dictionary necessary to configure instance market selection
    (on-demand vs spot)
    See:
    https://boto3.readthedocs.io/en/latest/reference/services/ec2.html#EC2.ServiceResource.create_instances
    and
    https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_InstanceMarketOptionsRequest.html
    """
    instmarkoptions = dict()
    if instancemarket == "spot":
        instmarkoptions['MarketType'] = "spot"
        instmarkoptions['SpotOptions'] = dict()
        if spotmaxprice != "ondemand":
            # no value for MaxPrice means ondemand, so fill it in if spotmaxprice is not ondemand
            instmarkoptions['SpotOptions']['MaxPrice'] = spotmaxprice
        if spotinterruptionbehavior != "terminate":
            # if you have special interruption behavior, we also need to make the instance persistent
            instmarkoptions['SpotOptions']['InstanceInterruptionBehavior'] = spotinterruptionbehavior
            instmarkoptions['SpotOptions']['SpotInstanceType'] = "persistent"
        return instmarkoptions
    elif instancemarket == "ondemand":
        # empty dict = on demand
        return instmarkoptions
    else:
        assert False, "INVALID INSTANCE MARKET TYPE."

def launch_instances(instancetype, count, instancemarket, spotinterruptionbehavior, spotmaxprice, blockdevices=None,
                     tags=None, randomsubnet=False, user_data_file=None, timeout=timedelta(), always_expand=True):
    """ Launch `count` instances of type `instancetype`

    Using `instancemarket`, `spotinterruptionbehavior` and `spotmaxprice` to define instance market conditions
    (see also: construct_market_conditions)

    This will launch instances in avail zone 0, then once capacity runs out, zone 1, then zone 2, etc.
    The ordering of availablility zones can be randomized by passing`randomsubnet=True`

    Parameters
    ----------
    instancetype : str
        String acceptable by `boto3.ec2.create_instances()` `InstanceType` parameter
    count : int
        The number of instances to launch
    instancemarket
    spotinterruptionbehavior
    spotmaxprice
    blockdevices
    tags : dict of tag names to string values, default=None
        Dict of tags
    randomsubnet : bool, default=False
        If true, subnets will be chosen randomly instead of starting from 0 and proceeding incrementally.
    user_data_file : str, default=None
        Path to readable file.  Contents of file are passed as `UserData` to AWS
    timeout : datetime.timedelta, default=timedelta() (immediate timeout after attempting all subnets)
        `timedelta` object representing how long we should continue to try asking for instances
    always_expand : bool, default=True
        When true, create `count` instances, regardless of whether any already exist. When False, only
        create instances until there are `count` total instances that match `tags` and `instancetype`
        If `tags` are not passed, `always_expand` must be `True` or `ValueError` is thrown.

    Return type
    -----------
    list(boto.ec2.Instance)

    Returns
    -------
    list of instance resources.  If `always_expand` is True, this list contains only the instances created in this
    call.  When `always_expand` is False, it contains all instances matching `tags` whether created in this call or not
    """

    if tags is None and not always_expand:
        raise ValueError("always_expand=False requires tags to be given")


    aws_resource_names_dict = aws_resource_names()
    keyname = aws_resource_names_dict['keyname']
    securitygroupname = aws_resource_names_dict['securitygroupname']
    vpcname = aws_resource_names_dict['vpcname']

    ec2 = boto3.resource('ec2')
    client = boto3.client('ec2')

    vpcfilter = [{'Name':'tag:Name', 'Values': [vpcname]}]
    firesimvpc = list(ec2.vpcs.filter(Filters=vpcfilter))
    subnets = list(firesimvpc[0].subnets.filter())
    if randomsubnet:
        random.shuffle(subnets)
    firesimsecuritygroup = client.describe_security_groups(
        Filters=[{'Name':'group-name', 'Values': [securitygroupname]}])['SecurityGroups'][0]['GroupId']

    marketconfig = construct_instance_market_options(instancemarket, spotinterruptionbehavior, spotmaxprice)
    f1_image_id = get_f1_ami_id()

    if blockdevices is None:
        blockdevices = []

    # starting with the first subnet, keep trying until you get the instances you need
    startsubnet = 0

    if tags and not always_expand:
        instances = instances_sorted_by_avail_ip(get_instances_by_tag_type(tags, instancetype))
    else:
        instances = []

    if len(instances):
        rootLogger.info("Already have {} of {} {} instances.".format(len(instances), count, instancetype))
        if len(instances) < count:
            rootLogger.info("Launching remaining {} {} instances".format(count - len(instances), instancetype))

    first_subnet_wraparound = None

    while len(instances) < count:
        chosensubnet = subnets[startsubnet].subnet_id
        try:
            instance_args = {"ImageId":f1_image_id,
                "EbsOptimized":True,
                "BlockDeviceMappings":(blockdevices + [
                    {
                        'DeviceName': '/dev/sdb',
                        'NoDevice': '',
                    },
                ]),
                "InstanceType":instancetype,
                "MinCount":1,
                "MaxCount":1,
                "NetworkInterfaces":[
                    {'SubnetId': chosensubnet,
                     'DeviceIndex':0,
                     'AssociatePublicIpAddress':True,
                     'Groups':[firesimsecuritygroup]}
                ],
                "KeyName":keyname,
                "TagSpecifications":([] if tags is None else [
                    {
                        'ResourceType': 'instance',
                        'Tags': [{ 'Key': k, 'Value': v} for k, v in tags.items()],
                    },
                ]),
                "InstanceMarketOptions":marketconfig,
            }
            if user_data_file:
                with open(user_data_file, "r") as f:
                    instance_args["UserData"] = ''.join(f.readlines())

            instance = ec2.create_instances(**instance_args)
            instances += instance

        except client.exceptions.ClientError as e:
            rootLogger.debug(e)
            startsubnet += 1
            if (startsubnet < len(subnets)):
                rootLogger.debug("This probably means there was no more capacity in this availability zone. Trying the next one.")
            else:
                rootLogger.info("Tried all subnets, but there was insufficient capacity to launch your instances")
                startsubnet = 0
                if first_subnet_wraparound is None:
                    # so that we are guaranteed that the default timeout of `timedelta()` aka timedelta(0)
                    # will cause timeout the very first time, we make the first_subnet_wraparound happen a bit in the
                    # past
                    first_subnet_wraparound = datetime.now() - timedelta(microseconds=1)

                time_elapsed = datetime.now() - first_subnet_wraparound
                rootLogger.info("have been trying for {} using timeout of {}".format(time_elapsed, timeout))
                rootLogger.info("""only {} of {} {} instances have been launched""".format(len(instances), count, instancetype))
                if time_elapsed > timeout:
                    rootLogger.critical("""Aborting! only the following {} instances were launched""".format(len(instances)))
                    rootLogger.critical(instances)
                    rootLogger.critical("To continue trying to allocate instances, you can rerun launchrunfarm")
                    sys.exit(1)
                else:
                    rootLogger.info("Will keep trying after sleeping for a bit...")
                    time.sleep(30)
                    rootLogger.info("Continuing to request remaining {}, {} instances".format(count - len(instances), instancetype))
    return instances

def run_block_device_dict():
    return [ { 'DeviceName': '/dev/sda1', 'Ebs': { 'VolumeSize': 300, 'VolumeType': 'gp2' } } ]

def run_tag_dict():
    return { 'fsimcluster': "defaultcluster" }

def run_filters_list_dict():
    return [ { 'Name': 'tag:fsimcluster', 'Values': [ "defaultcluster" ] } ]

def launch_run_instances(instancetype, count, fsimclustertag, instancemarket, spotinterruptionbehavior, spotmaxprice, timeout, always_expand):
    return launch_instances(instancetype, count, instancemarket, spotinterruptionbehavior, spotmaxprice, timeout=timeout, always_expand=always_expand,
        blockdevices=[
            {
                'DeviceName': '/dev/sda1',
                'Ebs': {
                    'VolumeSize': 300,  # TODO: make this configurable from .ini?
                    'VolumeType': 'gp2',
                },
            },
        ],
        tags={ 'fsimcluster': fsimclustertag })

def get_instances_with_filter(filters, allowed_states=['pending', 'running', 'shutting-down', 'stopping', 'stopped']):
    """ Produces a list of instances based on a set of provided filters """
    ec2_client = boto3.client('ec2')

    instance_res = ec2_client.describe_instances(Filters=filters +
        [{'Name': 'instance-state-name', 'Values' : allowed_states}]
    )['Reservations']

    instances = []
    # Collect all instances across all reservations
    if instance_res:
        for res in instance_res:
            if res['Instances']:
                instances.extend(res['Instances'])
    return instances

def get_run_instances_by_tag_type(fsimclustertag, instancetype):
    """ return list of instances that match fsimclustertag and instance type """
    return get_instances_by_tag_type(
        tags={'fsimcluster': fsimclustertag},
        instancetype=instancetype
    )

def get_instances_by_tag_type(tags, instancetype):
    """ return list of instances that match all tags and instance type """
    res = boto3.resource('ec2')

    instances = res.instances.filter(
        Filters = [
            {
                'Name': 'instance-type',
                'Values': [
                    instancetype,
                ]
            },
            {
                'Name': 'instance-state-name',
                'Values': [
                    'running',
                ]
            },
        ]
            +
        [
            {
                'Name': 'tag:{}'.format(k),
                'Values': [
                    v,
                ]
            } for k, v in tags.items()
        ]
    )
    return instances

def get_private_ips_for_instances(instances):
    """" Take list of instances (as returned by create_instances), return private IPs. """
    return [instance.private_ip_address for instance in instances]

def get_instance_ids_for_instances(instances):
    """" Take list of instances (as returned by create_instances), return instance ids. """
    return [instance.id for instance in instances]

def instances_sorted_by_avail_ip(instances):
    """ This returns a list of instance objects, first sorted by their private ip,
    then sorted by availability zone. """
    ips = get_private_ips_for_instances(instances)
    ips_to_instances = zip(ips, instances)
    insts = sorted(ips_to_instances, key=lambda x: x[0])
    insts = [x[1] for x in insts]
    return sorted(insts, key=lambda x: x.placement['AvailabilityZone'])

def instance_privateip_lookup_table(instances):
    """ Given a list of instances, construct a lookup table that goes from
    privateip -> instance obj """
    ips = get_private_ips_for_instances(instances)
    ips_to_instances = zip(ips, instances)
    return { ip: instance for (ip, instance) in ips_to_instances }

def wait_on_instance_boot(instance_id):
    """ Blocks on EC2 instance boot """
    ec2_client = boto3.client('ec2')
    waiter = ec2_client.get_waiter('instance_status_ok')
    waiter.wait(InstanceIds=[instance_id])

def wait_on_instance_launches(instances, message=""):
    """ Take a list of instances (as returned by create_instances), wait until
    instance is running. """
    rootLogger.info("Waiting for instance boots: " + str(len(instances)) + " " + message)
    for instance in instances:
        instance.wait_until_running()
        wait_on_instance_boot(instance.id)
        rootLogger.info(str(instance.id) + " booted!")

def terminate_instances(instanceids, dryrun=True):
    """ Terminate instances when given a list of instance ids.  for safety,
    this supplies dryrun=True by default. """
    client = boto3.client('ec2')
    client.terminate_instances(InstanceIds=instanceids, DryRun=dryrun)

def auto_create_bucket(userbucketname):
    """ Check if the user-specified s3 bucket is available.
    If we get a NoSuchBucket exception, create the bucket for the user.
    If we get any other exception, exit.
    If we get no exception, assume the bucket exists and the user has already
    set it up correctly.
    """
    s3cli = boto3.client('s3')
    try:
        s3cli.head_bucket(Bucket=userbucketname)
    except s3cli.exceptions.ClientError as exc:
        if 'Forbidden' in repr(exc):
            rootLogger.critical("You tried to access a bucket that is Forbidden. This probably means that someone else has taken the name already.")
            rootLogger.critical("The full exception is printed below:")
            rootLogger.critical("____________________________________________________________")
            rootLogger.critical(repr(exc))
            assert False

        elif 'Not Found' in repr(exc):
            # create the bucket for the user and setup directory structure
            rootLogger.info("Creating s3 bucket for you named: " + userbucketname)
            my_session = boto3.session.Session()
            my_region = my_session.region_name

            # yes, this is unfortunately the correct way of handling this.
            # you cannot pass 'us-east-1' as a location constraint because
            # it is a special case default. See
            # https://github.com/boto/boto3/issues/125#issuecomment-225720089
            if my_region == 'us-east-1':
                s3cli.create_bucket(Bucket=userbucketname)
            else:
                s3cli.create_bucket(Bucket=userbucketname,
                                    CreateBucketConfiguration={'LocationConstraint': my_region}
                                    )

            # now, setup directory structure
            resp = s3cli.put_object(
                Bucket = userbucketname,
                Body = '',
                Key = 'dcp/'
            )
            resp2 = s3cli.put_object(
                Bucket = userbucketname,
                Body = '',
                Key = 'logs/'
            )

        else:
            rootLogger.critical("Unknown error creating bucket. Please report this issue on the FireSim Github repo.")
            rootLogger.critical("The full exception is printed below:")
            rootLogger.critical("____________________________________________________________")
            rootLogger.critical(repr(exc))
            assert False

def get_snsname_arn():
    """ If the Topic doesn't exist create it, send catch exceptions while creating. Or if it exists get arn """
    client = boto3.client('sns')

    aws_resource_names_dict = aws_resource_names()
    snsname = aws_resource_names_dict['snsname']

    response = None
    try: # this will either create the topic, if it doesn't exist, or just get the arn
        response = client.create_topic(
            Name=snsname
        )
    except client.exceptions.ClientError as err:
        if 'AuthorizationError' in repr(err):
            rootLogger.warning("You don't have permissions to perform \"Topic Creation \". Required to send you email notifications. Please contact your IT administrator")
        else:
            rootLogger.warning("Unknown exception is encountered while trying to perform \"Topic Creation\"")
        rootLogger.warning(err)
        return None

    return response['TopicArn']

def subscribe_to_firesim_topic(email):
    """ Subscribe a user to their FireSim SNS topic for notifications. """

    client = boto3.client('sns')
    arn = get_snsname_arn()
    if not arn:
        return None
    try:
        response = client.subscribe(
            TopicArn=arn,
            Protocol='email',
            Endpoint=email
        )
        message = """You should receive a message at {}
asking to confirm your subscription to FireSim SNS Notifications. You will not
receive any notifications until you click the confirmation link.""".format(email)

        rootLogger.info(message)
    except client.exceptions.ClientError as err:
        if 'AuthorizationError' in repr(err):
            rootLogger.warning("You don't have permissions to subscribe to firesim notifications")
        else:
            rootLogger.warning("Unknown exception is encountered while trying subscribe notifications")
        rootLogger.warning(err)


def send_firesim_notification(subject, body):

    client = boto3.client('sns')
    arn = get_snsname_arn()

    if not arn:
        return None

    try:
        response = client.publish(
            TopicArn=arn,
            Message=body,
            Subject=subject
        )
    except client.exceptions.ClientError as err:
        if 'AuthorizationError' in repr(err):
            rootLogger.warning("You don't have permissions to publish to firesim notifications")
        else:
            rootLogger.warning("Unknown exception is encountered while trying publish notifications")
        rootLogger.warning(err)

def main(args):
    import argparse
    import yaml
    parser = argparse.ArgumentParser(description="Launch/terminate instances", formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("command", choices=["launch", "terminate"], help="Choose to launch or terminate instances")
    parser.add_argument("--inst_type", default="m5.large", help="Instance type. Used by \'launch\'.")
    parser.add_argument("--inst_amt", type=int, default=1, help="Number of instances to launch. Used by \'launch\'.")
    parser.add_argument("--market", choices=["ondemand", "spot"], default="ondemand", help="Type of market to get instances. Used by \'launch\'.")
    parser.add_argument("--int_behavior", choices=["hibernate", "stop", "terminate"], default="terminate", help="Interrupt behavior. Used by \'launch\'.")
    parser.add_argument("--spot_max_price", default="ondemand", help="Spot Max Price. Used by \'launch\'.")
    parser.add_argument("--random_subnet", action="store_true", help="Randomize subnets. Used by \'launch\'.")
    parser.add_argument("--block_devices", type=yaml.safe_load, default=run_block_device_dict(), help="List of dicts with block device information. Used by \'launch\'.")
    parser.add_argument("--tags", type=yaml.safe_load, default=run_tag_dict(), help="Dict of tags to add to instances. Used by \'launch\'.")
    parser.add_argument("--filters", type=yaml.safe_load, default=run_filters_list_dict(), help="List of dicts used to filter instances. Used by \'terminate\'.")
    parser.add_argument("--user_data_file", default=None, help="File path to use as user data (run on initialization). Used by \'launch\'.")
    args = parser.parse_args(args)

    if args.command == "launch":
        insts = launch_instances(
            args.inst_type,
            args.inst_amt,
            args.market,
            args.int_behavior,
            args.spot_max_price,
            args.block_devices,
            args.tags,
            args.random_subnet,
            args.user_data_file)
        instids = get_instance_ids_for_instances(insts)
        print("Instance IDs: {}".format(instids))
        wait_on_instance_launches(insts)
        print("Launched instance IPs: {}".format(get_private_ips_for_instances(insts)))
    else: # "terminate"
        insts = get_instances_with_filter(args.filters)
        instids = [ inst['InstanceId'] for inst in insts ]
        terminate_instances(instids, False)
        print("Terminated instance IDs: {}".format(instids))
    return 0

if __name__ == '__main__':
    import sys
    sys.exit(main(sys.argv[1:]))
