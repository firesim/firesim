from __future__ import print_function

import random
import logging

import boto3
import botocore
from botocore import exceptions
from fabric.api import local

rootLogger = logging.getLogger()

# users are instructed to create a key named `firesim` in the wiki
keyname = 'firesim'

# this needs to be updated whenever the FPGA Dev AMI changes
f1_ami_name = "FPGA Developer AMI - 1.5.0-40257ab5-6688-4c95-97d1-e251a40fd1fc-ami-06cecb61c79496e0d.4"

# users are instructed to create these in the setup instructions
securitygroupname = 'firesim'
vpcname = 'firesim'

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

def launch_instances(instancetype, count, instancemarket, spotinterruptionbehavior, spotmaxprice, blockdevices=None, tags=None, randomsubnet=False):
    """ Launch count instances of type instancetype, optionally with additional
    block devices mappings and instance tags

         This will launch instances in avail zone 0, then once capacity runs out, zone 1, then zone 2, etc.
    """
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
    instances = []
    while len(instances) < count:
        if not (startsubnet < len(subnets)):
            rootLogger.critical("we tried all subnets, but there was insufficient capacity to launch your instances")
            rootLogger.critical("""only the following {} instances were launched""".format(len(instances)))
            rootLogger.critical(instances)
            return

        chosensubnet = subnets[startsubnet].subnet_id
        try:
            instance = ec2.create_instances(ImageId=f1_image_id,
                            EbsOptimized=True,
                            BlockDeviceMappings=(blockdevices + [
                                {
                                    'DeviceName': '/dev/sdb',
                                    'NoDevice': '',
                                },
                            ]),
                            InstanceType=instancetype, MinCount=1, MaxCount=1,
                            NetworkInterfaces=[
                                {'SubnetId': chosensubnet,
                                 'DeviceIndex':0,
                                 'AssociatePublicIpAddress':True,
                                 'Groups':[firesimsecuritygroup]}
                            ],
                            KeyName=keyname,
                            TagSpecifications=([] if tags is None else [
                                {
                                    'ResourceType': 'instance',
                                    'Tags': [{ 'Key': k, 'Value': v} for k, v in tags.items()],
                                },
                            ]),
                            InstanceMarketOptions=marketconfig
                        )
            instances += instance

        except client.exceptions.ClientError as e:
            rootLogger.info(e)
            rootLogger.info("This probably means there was no more capacity in this availability zone. Try the next one.")
            startsubnet += 1
    return instances

def launch_run_instances(instancetype, count, fsimclustertag, instancemarket, spotinterruptionbehavior, spotmaxprice):
    return launch_instances(instancetype, count, instancemarket, spotinterruptionbehavior, spotmaxprice,
        blockdevices=[
            {
                'DeviceName': '/dev/sda1',
                'Ebs': {
                    'VolumeSize': 300,
                    'VolumeType': 'gp2',
                },
            },
        ],
        tags={ 'fsimcluster': fsimclustertag })

def get_instances_by_tag_type(fsimclustertag, instancetype):
    """ return list of instances that match a tag and instance type """
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
            {
                'Name': 'tag:fsimcluster',
                'Values': [
                    fsimclustertag,
                ]
            },
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

def wait_on_instance_launches(instances, message=""):
    """ Take a list of instances (as returned by create_instances), wait until
    instance is running. """
    rootLogger.info("Waiting for instance boots: " + str(len(instances)) + " " + message)
    for instance in instances:
        instance.wait_until_running()
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

def subscribe_to_firesim_topic(email):
    """ Subscribe a user to their FireSim SNS topic for notifications. """
    client = boto3.client('sns')

    # this will either create the topic, if it doesn't exist, or just get the arn
    response = client.create_topic(
        Name='FireSim'
    )
    arn = response['TopicArn']

    response = client.subscribe(
        TopicArn=arn,
        Protocol='email',
        Endpoint=email
    )

    message = """You should receive a message at
{}
asking to confirm your subscription to FireSim SNS Notifications. You will not
receive any notifications until you click the confirmation link.""".format(email)

    rootLogger.info(message)

def send_firesim_notification(subject, body):
    """ Send a FireSim SNS Email notification. """
    client = boto3.client('sns')

    # this will either create the topic, if it doesn't exist, or just get the arn
    response = client.create_topic(
        Name='FireSim'
    )

    arn = response['TopicArn']

    response = client.publish(
        TopicArn=arn,
        Message=body,
        Subject=subject
    )

if __name__ == '__main__':
    #""" Example usage """
    #instanceobjs = launch_instances('c4.4xlarge', 2)
    #instance_ips = get_private_ips_for_instances(instanceobjs)
    #instance_ids = get_instance_ids_for_instances(instanceobjs)
    #wait_on_instance_launches(instanceobjs)

    #print("now terminating!")
    #terminate_instances(instance_ids, False)

    """ Test SNS """
    #subscribe_to_firesim_topic("sagark@eecs.berkeley.edu")

    send_firesim_notification("test subject", "test message")
