from pathlib import Path
from unittest.mock import MagicMock, call
from util.fsspec_utils import downloadURI
from fsspec.implementations.local import LocalFileSystem


def test_download_uri(mocker):
    logger_mock = mocker.patch("util.fsspec_utils.rootLogger", MagicMock())
    local_test_path = Path("tests/spec_results_download.csv")
    test_uri = "https://www.spec.org/cpu2017/results/res2018q1/cpu2017-20171224-02028.csv"

    fs = LocalFileSystem()

    if local_test_path.exists():
        fs.rm(str(local_test_path))

    downloadURI(
        uri=test_uri,
        local_dest_path=local_test_path
    )

    assert fs.exists(local_test_path), f"{local_test_path} was not created."

    logger_mock.debug.assert_called_once_with(f"Downloading '{test_uri}' to '{local_test_path}'")

    downloadURI(
        uri=test_uri,
        local_dest_path=local_test_path
    )

    logger_mock.debug.assert_has_calls([
        call(f"Downloading '{test_uri}' to '{local_test_path}'"),
        call(f"Overwriting {local_test_path.resolve()}"),
        call(f"Downloading '{test_uri}' to '{local_test_path}'")
    ])

    fs.rm(str(local_test_path))
