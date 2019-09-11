#!/usr/bin/env python

import boto3
import argparse
from datetime import datetime
import pytz

def get_current_region():
    boto_session = boto3.session.Session()
    return boto_session.region_name

class ImageDeleter(object):
    def __init__(self, region=None):
        self.region = region if region is not None else get_current_region()
        self.client = boto3.client("ec2", region_name=self.region)
        self.deleted = []

    def get_customer_id(self):
        client = boto3.client("sts")
        account_id = client.get_caller_identity()["Account"]
        return account_id

    def get_images_for_agfis(self, agfis):
        account_id = self.get_customer_id()
        result = self.client.describe_fpga_images(
            Filters=[
                {
                    "Name": "fpga-image-global-id",
                    "Values": agfis
                },
                {
                    "Name": "owner-id",
                    "Values": [account_id]
                }

            ]
        )

        return [image for image in result["FpgaImages"] if not image["Public"]]

    def delete_agfi(self, agfi):
        images = self.get_images_for_agfis([agfi])

        if len(images) == 0:
            print("Could not find image {}".format(agfi))
            return

        image = images[0]

        print("{} {}".format(image["FpgaImageId"], image["Description"]))
        print("You are about to delete this image")

        proceed = raw_input("Are you sure you want to proceed? (yes/no): ")
        if proceed == "yes":
            afi = image["FpgaImageId"]
            print("Delete {}".format(afi))
            self.client.delete_fpga_image(FpgaImageId = afi)
            self.deleted.append(afi)

    def delete_agfis(self, agfis):
        images = self.get_images_for_agfis(agfis)

        for image in images:
            print("{} {}".format(image["FpgaImageId"], image["Description"]))

        print("You are about to delete these {} images".format(len(images)))

        proceed = raw_input("Are you sure you want to proceed? (yes/no): ")
        if proceed == "yes":
            for image in images:
                afi = image["FpgaImageId"]
                print("Delete {}".format(afi))
                self.client.delete_fpga_image(FpgaImageId = afi)
                self.deleted.append(afi)

    def list_images_before(self, before):
        account_id = self.get_customer_id()
        result = self.client.describe_fpga_images(
            Filters=[
                {
                    "Name": "owner-id",
                    "Values": [account_id]
                }

            ]
        )
        images = []

        for image in result["FpgaImages"]:
            # Skip public images or images owned by others
            if image["Public"]:
                continue

            # Check if image is shared with others, and skip if so
            attribute = self.client.describe_fpga_image_attribute(
                    FpgaImageId = image["FpgaImageId"],
                    Attribute = "loadPermission")
            loadPermissions = attribute["FpgaImageAttribute"]["LoadPermissions"]
            created = image["CreateTime"]

            if not loadPermissions and created < before:
                images.append(image)

        return images

    def delete_before(self, before):
        images = self.list_images_before(before)

        for image in images:
            print("{} {} {}".format(
                image["FpgaImageGlobalId"],
                image["FpgaImageId"],
                image["Description"]))
        print("You are about to delete {} images.".format(len(images)))
        proceed = raw_input("Are you sure you want to continue? (yes/no): ")

        if proceed == "yes":
            for image in images:
                afi = image["FpgaImageId"]
                print("Delete {}".format(afi))
                self.client.delete_fpga_image(FpgaImageId=afi)
                self.deleted.append(afi)

def main():
    parser = argparse.ArgumentParser(description="Tool for deleting F1 FPGA images")
    parser.add_argument("--agfi", help="Global FPGA image ID")
    parser.add_argument("--before", help="Delete all images created before this date (YYYY-mm-dd)")
    parser.add_argument("--list", help="Delete AGFIs from list")
    parser.add_argument("--region", help="EC2 Region to resolve AGFIs in")
    args = parser.parse_args()

    deleter = ImageDeleter(args.region)
    if args.agfi:
        deleter.delete_agfi(args.agfi)
    elif args.list:
        with open(args.list) as f:
            agfis = [s.strip() for s in f]
            deleter.delete_agfis(agfis)
    elif args.before:
        datefmt = "%Y-%m-%d"
        before = datetime.strptime(args.before, datefmt)
        before = before.replace(tzinfo=pytz.UTC)
        deleter.delete_before(before)

    print("Deleted {} images".format(len(deleter.deleted)))

if __name__ == "__main__":
    main()
