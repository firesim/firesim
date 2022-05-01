#!/usr/bin/env python3

from __future__ import annotations

""" This script configures your AWS account to run FireSim. """

import boto3

vpcname = 'firesim'
secgroupname = 'firesim'

def aws_setup():
    ec2 = boto3.resource('ec2')
    client = boto3.client('ec2')

    # get list of avail zones in the region, we will need it later
    avail_zones = list(map(lambda x: x['ZoneName'], client.describe_availability_zones()['AvailabilityZones']))
    av_zones_with_3octet = zip(range(len(avail_zones)), avail_zones)

    print("Creating VPC for FireSim...")
    vpc = ec2.create_vpc(CidrBlock='192.168.0.0/16')
    vpc_id = vpc.id
    # confirm that vpc is actually available before running commands
    client.get_waiter('vpc_exists').wait(VpcIds=[vpc_id])
    client.get_waiter('vpc_available').wait(VpcIds=[vpc_id])

    vpc.create_tags(Tags=[{"Key": "Name", "Value": vpcname}])
    vpc.wait_until_available()

    ig = ec2.create_internet_gateway()
    vpc.attach_internet_gateway(InternetGatewayId=ig.id)

    route_table = vpc.create_route_table()
    route = route_table.create_route(
        DestinationCidrBlock='0.0.0.0/0',
        GatewayId=ig.id
    )
    print("Success!")

    print("Creating a subnet in the VPC for each availability zone...")
    subnets = []
    # create a subnet in each availability zone for this vpc
    for ip, zone in av_zones_with_3octet:
        subnets.append(ec2.create_subnet(CidrBlock='192.168.' + str(ip) + '.0/24', VpcId=vpc.id, AvailabilityZone=zone))
        client.get_waiter('subnet_available').wait(SubnetIds=[subnets[-1].id])
        client.modify_subnet_attribute(MapPublicIpOnLaunch={'Value': True}, SubnetId=subnets[-1].id)
        route_table.associate_with_subnet(SubnetId=subnets[-1].id)
    print("Success!")

    print("Creating a security group for FireSim...")
    sec_group = ec2.create_security_group(
        GroupName=secgroupname, Description='firesim security group', VpcId=vpc.id)

    # allow all egress rule exists by default

    # ingress rules
    sec_group.authorize_ingress(IpPermissions=[
        {u'PrefixListIds': [], u'FromPort': 60000, u'IpRanges': [{u'Description': 'mosh', u'CidrIp': '0.0.0.0/0'}], u'ToPort': 61000, u'IpProtocol': 'udp', u'UserIdGroupPairs': [], u'Ipv6Ranges': [{u'Description': 'mosh', u'CidrIpv6': '::/0'}]},
        {u'PrefixListIds': [], u'FromPort': 22, u'IpRanges': [{u'CidrIp': '0.0.0.0/0'}], u'ToPort': 22, u'IpProtocol': 'tcp', u'UserIdGroupPairs': [], u'Ipv6Ranges': []},
        {u'PrefixListIds': [], u'FromPort': 10000, u'IpRanges': [{u'Description': 'firesim network model', u'CidrIp': '0.0.0.0/0'}], u'ToPort': 11000, u'IpProtocol': 'tcp', u'UserIdGroupPairs': [], u'Ipv6Ranges': [{u'Description': 'firesim network model', u'CidrIpv6': '::/0'}]},
        {u'PrefixListIds': [], u'FromPort': 3389, u'IpRanges': [{u'Description': 'remote desktop', u'CidrIp': '0.0.0.0/0'}], u'ToPort': 3389, u'IpProtocol': 'tcp', u'UserIdGroupPairs': [], u'Ipv6Ranges': [{u'CidrIpv6': '::/0', u'Description': 'rdp'}]}])

    print("Success!")

if __name__ == '__main__':
    aws_setup()
