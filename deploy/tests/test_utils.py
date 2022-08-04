from pathlib import Path
from unittest.mock import MagicMock, call
from util.io import downloadURI
from botocore.exceptions import ClientError
import pytest
import os

from moto import mock_s3
import boto3

pytest.mark.usefixtures("aws_test_credentials")


@pytest.mark.parametrize(
    'protocol_type,test_dest_file_path',
    [
        ('s3',Path("tests/s3_test_download_json.json")),
        ('file',Path("tests/file_test_download_json.json")),
    ]
)
@mock_s3
def test_download_uri(mocker,protocol_type,test_dest_file_path):
    logger_mock = mocker.patch("util.io.rootLogger", MagicMock())
    test_file_path = Path("tests/fsspec_test_json.json")
    
    if protocol_type == 's3':
        try:
            test_bucket = "TestBucket"
            test_bucket_key = "s3_blob.json"
            mock_s3_client = boto3.client('s3')
            mock_s3_client.create_bucket(Bucket="TestBucket")
            mock_s3_client.upload_file(str(test_file_path), test_bucket, test_bucket_key)
            file_uri = f"s3://{test_bucket}/{test_bucket_key}"
        except ClientError as e:
            pytest.fail("Failed to mock an S3 client and upload a file.")

    if protocol_type == 'file':
        file_uri = f"file://{test_file_path}"

    if test_dest_file_path.exists():
        os.remove(str(test_dest_file_path))

    downloadURI(
        uri=file_uri,
        local_dest_path=test_dest_file_path
    )

    assert os.path.exists(test_dest_file_path), f"{test_dest_file_path} was not created."

    logger_mock.debug.assert_called_once_with(f"Downloading '{file_uri}' to '{test_dest_file_path}'")

    downloadURI(
        uri=file_uri,
        local_dest_path=test_dest_file_path
    )

    logger_mock.debug.assert_has_calls([
        call(f"Downloading '{file_uri}' to '{test_dest_file_path}'"),
        call(f"Overwriting {test_dest_file_path.resolve()}"),
        call(f"Downloading '{file_uri}' to '{test_dest_file_path}'")
    ])

    os.remove(str(test_dest_file_path))
