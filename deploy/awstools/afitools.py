"""Tools to help manage afis."""

from __future__ import annotations

import logging
import boto3
from awstools.awstools import depaginated_boto_query

rootLogger = logging.getLogger()

def get_fpga_regions():
    """ Get list of all regions with F1 support """
    fpga_regions = [
        'us-east-1',       # US East (N. Virginia)
        'us-west-2',       # US West (Oregon)
        'eu-west-1',       # Europe (Ireland)
        'ap-southeast-2',  # Asia Pacific (Sydney)
    ]
    return list(fpga_regions)

def get_current_region():
    boto_session = boto3.session.Session()
    return boto_session.region_name

def get_afi_for_agfi(agfi_id, region=None):
    """ Get the AFI for the AGFI in the specified region.
    region = None means use default region
    (AFIs are region specific, AGFIs are global).
    """
    rootLogger.debug(agfi_id)
    rootLogger.debug(region)
    region = region if region is not None else get_current_region()

    client = boto3.client('ec2', region_name=region)
    operation_params = {
        'Filters': [
            {
                'Name': 'fpga-image-global-id',
                'Values': [
                    agfi_id
                ]
            },
        ]
    }
    fpga_images_all = depaginated_boto_query(client, 'describe_fpga_images', operation_params, 'FpgaImages')
    rootLogger.debug(fpga_images_all)
    return fpga_images_all[0]['FpgaImageId']

def copy_afi_to_all_regions(afi_id, starting_region=None):
    """ Copies an AFI to all regions, excluding the specified region.
    starting_region=None makes starting region = to the default region. """
    starting_region = starting_region if starting_region is not None else get_current_region()
    rootLogger.info("""Copy starting region is: {}""".format(starting_region))

    # list of regions to make the agfi available in
    # (make a copy)
    copy_to_regions = get_fpga_regions()

    # remove the starting region, otherwise aws will create duplicate afis
    copy_to_regions.remove(starting_region)

    rootLogger.info("""Regions to copy to: {}""".format(copy_to_regions))
    rootLogger.info("""Copying AFI: {}""".format(afi_id))

    for region in copy_to_regions:
        client = boto3.client('ec2', region_name=region)
        result = client.copy_fpga_image(DryRun=False,
                               SourceFpgaImageId=afi_id,
                               SourceRegion=starting_region)
        rootLogger.debug(result)
        rootLogger.info("Copy result: " + str(result['FpgaImageId']))


def share_afi_with_users(afi_id, region, useridlist):
    """ share the AFI in Region region with users in userlist. """
    client = boto3.client('ec2', region_name=region)
    if "public" in useridlist:
        rootLogger.info("Sharing AGFI publicly.")
        result = client.modify_fpga_image_attribute(
            FpgaImageId=afi_id,
            Attribute='loadPermission',
            OperationType='add',
            UserGroups=['all']
        )
    else:
        rootLogger.info("Sharing AGFI with selected users.")
        result = client.modify_fpga_image_attribute(
            FpgaImageId=afi_id,
            Attribute='loadPermission',
            OperationType='add',
            UserIds=list(map(str, useridlist))
        )
    rootLogger.debug(result)

def get_afi_sharing_ids_from_conf(conf):
    usersdict = conf.ini['agfisharing']
    return usersdict.values()

def share_agfi_in_all_regions(agfi_id, useridlist):
    """ For the given AGFI, for each fpga region, get the AFI, then share
    with the users in useridlist """
    all_fpga_regions = get_fpga_regions()
    for region in all_fpga_regions:
        afi_id = get_afi_for_agfi(agfi_id, region)
        share_afi_with_users(afi_id, region, useridlist)

def firesim_tags_to_description(build_quintuplet, deploy_quintuplet, build_triplet, deploy_triplet, commit):
    """ Serialize the tags we want to set for storage in the AGFI description """
    # note: the serialized rep still includes "triplets" for future manager versions to be compatible with old agfis
    return f"""firesim-buildquintuplet:{build_quintuplet},firesim-deployquintuplet:{deploy_quintuplet},firesim-buildtriplet:{build_triplet},firesim-deploytriplet:{deploy_triplet},firesim-commit:{commit}"""

def firesim_description_to_tags(description):
    """ Deserialize the tags we want to read from the AGFI description string.
    Return dictionary of keys/vals [{build,deploy}quintuplet, {build,deploy}triplet, commit]. """
    returndict = dict()
    desc_split = description.split(",")
    for keypair in desc_split:
        splitpair = keypair.split(":")
        returndict[splitpair[0]] = splitpair[1]
    return returndict

def get_firesim_tagval_for_afi(afi_id, tagkey):
    """ Given an afi_id, and tag key, return the FireSim tag value of the afi."""
    client = boto3.client('ec2')
    operation_params = {
        'FpgaImageIds': [
            afi_id
        ]
    }
    result = depaginated_boto_query(client, 'describe_fpga_images', operation_params, 'FpgaImages')[0]['Description']
    return firesim_description_to_tags(result).get(tagkey)

def get_firesim_tagval_for_agfi(agfi_id, tagkey):
    """ Given an agfi_id and tagkey, return the tagval. """
    afi_id = get_afi_for_agfi(agfi_id)
    return get_firesim_tagval_for_afi(afi_id, tagkey)

def get_firesim_deploy_quintuplet_for_agfi(agfi_id):
    """ Given an agfi_id, return the deploy_quintuplet. """
    quin = get_firesim_tagval_for_agfi(agfi_id, 'firesim-deployquintuplet')
    if quin is None:
        # for old AGFIs that use the old "triplet" key
        quin = get_firesim_tagval_for_agfi(agfi_id, 'firesim-deploytriplet')
    if len(quin.split("-")) == 3:
        # handle old AGFIs that only have triplet value:
        return 'f1-firesim-' + quin
    return quin

## Note that there are no set_firesim_tagval functions, because applying tags is
## done at create-fpga-image time

if __name__ == '__main__':
    pass
