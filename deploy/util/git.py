from typing import Tuple
from fabric.api import local, lcd, warn_only, hide # type: ignore
from fabric.utils import error, abort # type: ignore
import re

class GitServerSHA1Denial(Exception):
    """Used to indicate Git Server config doesn't allow efficient query in git_server_do_you_have_this"""

def git_sha_dirty(workdir:str = ".") -> Tuple[str, str]:
    with lcd(workdir):
        is_dirty_str = local("if [[ $(git status --porcelain) ]]; then echo '-dirty'; fi", capture=True)
        hash = local("git rev-parse HEAD", capture=True)
        return hash, is_dirty_str


def git_origin(workdir:str = ".") -> str:
    """
    Args:
        workdir: path to git repository working tree
    Returns:
        first fetch url of 'origin' remote in `workdir` or emptystring if it is not defined
    """
    with lcd(workdir), warn_only():
        origin = local("git remote get-url origin", capture=True)
        if origin.return_code != 0:
            origin = ""
        return origin


def git_server_do_you_have_this(remote_url:str, sha:str, workdir:str = ".") -> bool:
    """Query git at `remote_url` for existence of `sha`
    Notes:
        requires that your git server is configured with `uploadpack.allowReachableSHA1InWant` or
        (more likely due to the computational complexity of reachability) `uploadpack.allowAnySHA1InWant`
        If your server does not support requesting unadvertized shas, you will receive a `GitServerSHA1Denial`
        exception
    Args:
        remote_url: url of a git 'remote'
        sha: 40-hex sha string of a commit object
        workdir. Default is ".": path to git working tree
    Raises:
        `GitServerDenial` when server does not allow unadvertised objects to be requested
        `fabric.env.abort_exception` or `SystemExit` on unrecognized non-zero
        exit from `git fetch-pack`
    Returns:
        True if `sha` is found by `remote_url`, False if `sha` is `not our ref`
    """
    with lcd(workdir), warn_only():
        # NOTE: if the remote git server doesn't have at least uploadpack.allowReachableSHA1InWant, then
        #       this will turn into a false-negative.  It currently works for GitHub and there is
        #       test_git.py::TestGitAssumptions::test_git_fetch_pack_for_sha to ensure it continues to work
        fp_res = local(f"git fetch-pack --depth=1 {remote_url} {sha}", capture=True)
        if fp_res.return_code == 0:
            return True
        elif re.search(r'not our ref', fp_res.stderr):
            return False
        elif re.search(r'Server does not allow request for unadvertised object', fp_res.stderr):
            raise GitServerSHA1Denial(fp_res.stderr)
        else:
            # some other unexpected kind of error happened, throw like fabric
            # would if warn_only wasn't in place
            msg = f"local() encountered an error (return code {fp_res.return_code}) while executing '{fp_res.command}'"
            # force fabric to print stdout and stderr because we captured them and they weren't printed yet
            with hide('stdout', 'stderr'):
                error(message=msg, func=abort, stdout=fp_res, stderr=fp_res.stderr)

    return False # only to make mypy pass, this can never be reached.

def git_origin_sha_is_pushed(workdir:str = ".") -> Tuple[str, str, bool]:
    """Query git server regarding current commit checked out in `workdir`
    Args:
        workdir: run git at this path
    Returns:
        3-tuple of:
        * origin URL - empty string if origin remote does not exist
        * sha - 40 character git sho with -dirty suffix if status isn't clean
        * is_pushed - indication of whether sha exists on origin
    """
    hash, is_dirty_str = git_sha_dirty(workdir)
    origin = git_origin(workdir)
    is_pushed = git_server_do_you_have_this(origin, hash, workdir)

    return origin, hash + is_dirty_str, is_pushed
