from pathlib import Path
from unittest.mock import MagicMock, call
from util.io import downloadURI
from fsspec.implementations.local import LocalFileSystem
from botocore.exceptions import ClientError
import pytest

pytest.mark.usefixtures("asw_test_credentials")


def test_download_s3_uri(mocker, mock_s3_client):
    logger_mock = mocker.patch("util.io.rootLogger", MagicMock())
    test_file_path = Path("tests/s3_test_json.json")
    test_dest_file_path = Path("tests/s3_test_download_json.json")
    test_bucket = "TestBucket"
    test_bucket_key = "s3_blob.json"

    try:
        mock_s3_client.create_bucket(Bucket="TestBucket")
        mock_s3_client.upload_file(str(test_file_path), test_bucket, test_bucket_key)
        file_uri = f"s3://{test_bucket}/{test_bucket_key}"
    except ClientError as e:
        pytest.fail("Failed to mock an S3 client and upload a file.")

    fs = LocalFileSystem()

    if test_dest_file_path.exists():
        fs.rm(str(test_dest_file_path))

    downloadURI(
        uri=file_uri,
        local_dest_path=test_dest_file_path
    )

    assert fs.exists(test_dest_file_path), f"{test_dest_file_path} was not created."

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

    fs.rm(str(test_dest_file_path))
