import logging
from os import PathLike, fspath
from fsspec.core import url_to_fs, open_local # type: ignore
from pathlib import Path
from fabric.api import local # type: ignore
from typing import Optional
import time

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

rootLogger = logging.getLogger()

def downloadURI(uri: str, local_dest_path: str, tries: int = 4) -> None:
    """Uses the fsspec library to fetch a file specified in the uri to the local file system. Will throw if
    the file is not found.
    Args:
        uri: uri of an object to be fetched
        local_dest_path: path on the local file system to store the uri object
        tries: The number of times to try the download. A 1 second sleep will occur after each failure.
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
    fs, rpath = url_to_fs(uri)

    assert tries > 0, "tries argument must be larger than 0"
    for attempt in range(tries):
        rootLogger.debug(f"Download attempt {attempt+1} of {tries}: '{uri}' to '{lpath}'")
        try:
            fs.get_file(rpath, fspath(lpath)) # fspath() b.c. fsspec deals in strings, not PathLike
        except Exception as e:
            if attempt < tries -1:
                time.sleep(1) # Sleep only after a failure
                continue
            else:
                raise # tries have been exhausted, raise the last exception
        rootLogger.debug(f"Successfully fetched '{uri}' to '{lpath}'")
        break
