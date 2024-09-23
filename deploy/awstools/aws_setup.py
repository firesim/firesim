#!/usr/bin/env python3

from __future__ import annotations

""" This script configures your AWS account to run FireSim. """

import boto3

vpcname = "firesim"
secgroupname = "firesim"


def aws_setup():
    ec2 = boto3.resource("ec2")
    client = boto3.client("ec2")

    # get list of avail zones in the region, we will need it later
    avail_zones = list(
        map(
            lambda x: x["ZoneName"],
            client.describe_availability_zones()["AvailabilityZones"],
        )
    )
    av_zones_with_3octet = zip(range(len(avail_zones)), avail_zones)

    print("Creating VPC for FireSim...")
    vpc = ec2.create_vpc(CidrBlock="192.168.0.0/16")
    vpc_id = vpc.id
    # confirm that vpc is actually available before running commands
    client.get_waiter("vpc_exists").wait(VpcIds=[vpc_id])
    client.get_waiter("vpc_available").wait(VpcIds=[vpc_id])

    vpc.create_tags(Tags=[{"Key": "Name", "Value": vpcname}])
    vpc.wait_until_available()

    ig = ec2.create_internet_gateway()
    vpc.attach_internet_gateway(InternetGatewayId=ig.id)

    route_table = vpc.create_route_table()
    route = route_table.create_route(DestinationCidrBlock="0.0.0.0/0", GatewayId=ig.id)
    print("Success!")

    print("Creating a subnet in the VPC for each availability zone...")
    subnets = []
    # create a subnet in each availability zone for this vpc
    for ip, zone in av_zones_with_3octet:
        subnets.append(
            ec2.create_subnet(
                CidrBlock="192.168." + str(ip) + ".0/24",
                VpcId=vpc.id,
                AvailabilityZone=zone,
            )
        )
        client.get_waiter("subnet_available").wait(SubnetIds=[subnets[-1].id])
        client.modify_subnet_attribute(
            MapPublicIpOnLaunch={"Value": True}, SubnetId=subnets[-1].id
        )
        route_table.associate_with_subnet(SubnetId=subnets[-1].id)
    print("Success!")

    print("Creating a security group for FireSim...")
    sec_group = ec2.create_security_group(
        GroupName=secgroupname, Description="firesim security group", VpcId=vpc.id
    )

    # allow all egress rule exists by default

    # ingress rules
    sec_group.authorize_ingress(
        IpPermissions=[
            {
                "PrefixListIds": [],
                "FromPort": 60000,
                "IpRanges": [{"Description": "mosh", "CidrIp": "0.0.0.0/0"}],
                "ToPort": 61000,
                "IpProtocol": "udp",
                "UserIdGroupPairs": [],
                "Ipv6Ranges": [{"Description": "mosh", "CidrIpv6": "::/0"}],
            },
            {
                "PrefixListIds": [],
                "FromPort": 22,
                "IpRanges": [{"CidrIp": "0.0.0.0/0"}],
                "ToPort": 22,
                "IpProtocol": "tcp",
                "UserIdGroupPairs": [],
                "Ipv6Ranges": [],
            },
            {
                "PrefixListIds": [],
                "FromPort": 10000,
                "IpRanges": [
                    {"Description": "firesim network model", "CidrIp": "0.0.0.0/0"}
                ],
                "ToPort": 11000,
                "IpProtocol": "tcp",
                "UserIdGroupPairs": [],
                "Ipv6Ranges": [
                    {"Description": "firesim network model", "CidrIpv6": "::/0"}
                ],
            },
            {
                "PrefixListIds": [],
                "FromPort": 3389,
                "IpRanges": [{"Description": "remote desktop", "CidrIp": "0.0.0.0/0"}],
                "ToPort": 3389,
                "IpProtocol": "tcp",
                "UserIdGroupPairs": [],
                "Ipv6Ranges": [{"CidrIpv6": "::/0", "Description": "rdp"}],
            },
            {
                "PrefixListIds": [],
                "FromPort": 8443,
                "IpRanges": [{"Description": "nice dcv (ipv4)", "CidrIp": "0.0.0.0/0"}],
                "ToPort": 8443,
                "IpProtocol": "tcp",
                "UserIdGroupPairs": [],
                "Ipv6Ranges": [{"Description": "nice dcv (ipv6)", "CidrIpv6": "::/0"}],
            },
        ]
    )

    print("Success!")


if __name__ == "__main__":
    aws_setup()
