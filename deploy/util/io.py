import logging
from os import PathLike, fspath
from fsspec.core import url_to_fs # type: ignore
from pathlib import Path

def firesim_input(prompt: object = None) -> str:
    """wrap builtins.input() understanding the idiocyncracies of firesim+fabric+logging

    Log the prompt at CRITICAL level so that it will go to the terminal and the log.
    Log the entered text as DEBUG so that the log contains it.
    Don't pass the prompt to builtins.input() because we don't need StreamLogger to also
    be trying to log the prompt.
    
    See 'streamlogger.py' and it's use at the end of 'firesim.py'
    """

    rootLogger = logging.getLogger()
    if prompt:
        rootLogger.critical(prompt)

    res = input()
    rootLogger.debug("User Provided input():'%s'", res)

    return res


def downloadURI(uri: str, local_dest_path: PathLike) -> None:
    """Uses the fsspec library to fetch a file specified in the uri to the local file system.
    Args:
        uri: uri of an object to be fetched
        local_dest_path: path on the local file system to store the uri object
    """    

    rootLogger = logging.getLogger()

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
    fs.get_file(rpath, fspath(lpath)) # fspath() b.c. fsspec deals in strings, not PathLike
