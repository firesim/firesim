import logging
from os import PathLike, fspath
from fsspec.core import url_to_fs, open_local # type: ignore
from pathlib import Path
from fabric.api import local # type: ignore
from typing import Optional

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

def downloadURI(uri: str, local_dest_path: str) -> None:
    """Uses the fsspec library to fetch a file specified in the uri to the local file system. Will throw if
    the file is not found.
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
    fs.get_file(rpath, fspath(lpath)) # fspath() b.c. fsspec deals in strings, not PathLike

def downloadURICached(uri: str, local_dest_path: Optional[str]) -> None:
    """Uses the fsspec library to fetch a file specified in the uri to the local file system. Will throw if
    the file is not found. The result is cached; multiple identical invocations will result in a single download.
    simpelcache is used, which is guarenteed to be threadsafe:
    https://filesystem-spec.readthedocs.io/en/stable/features.html#caching-files-locally

    Args:
        uri: uri of an object to be fetched
        local_dest_path: path on the local file system to store the uri object. When None a download
        occurs only to warmup the cache.
    """
    if local_dest_path is not None:
        lpath = Path(local_dest_path)
        if lpath.exists():
            rootLogger.debug(f"Overwriting {lpath.resolve(strict=False)}")
        rootLogger.debug(f"Potentially cached download '{uri}' to '{lpath}'")
    else:
        rootLogger.debug(f"Warmup cache download of '{uri}'")
        

    # This does the actual download. A path to the local file is returned,
    # not a file handle
    local_path = open_local(f"simplecache::{uri}")
    
    # copy to requested location
    if local_dest_path is not None:
        local(f"""cp "{local_path}" "{local_dest_path}" """, capture=True)
