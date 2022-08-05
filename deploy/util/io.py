import logging
from os import PathLike, fspath
from fsspec.core import url_to_fs # type: ignore
from pathlib import Path

rootLogger = logging.getLogger()


def downloadURI(uri: str, local_dest_path: PathLike) -> None:
    """Uses the fsspec library to fetch a file specified in the uri to the local file system.
    Args:
        uri: uri of an object to be fetched
        local_dest_path: path on the local file system to store the uri object
    """    

    # TODO consider using fsspec
    # filecache https://filesystem-spec.readthedocs.io/en/latest/features.html#caching-files-locally
    # so that multiple slots using the same xclbin only grab it once and
    # we only download it if it has changed at the source.
    # HOWEVER, 'filecache' isn't thread/process safe and I'm not sure whether
    # this runs in @parallel for fabric
    lpath = Path(local_dest_path)
    if lpath.exists():
        rootLogger.debug(f"Overwriting {lpath.resolve(strict=False)}")
    rootLogger.debug(f"Downloading '{uri}' to '{lpath}'")
    fs, rpath = url_to_fs(uri)
    print(fs, type(fs))
    print(rpath, type(rpath))
    fs.get_file(rpath, fspath(lpath)) # fspath() b.c. fsspec deals in strings, not PathLike
