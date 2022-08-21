import logging
from os import fspath
import pytest
import re
from subprocess import run, PIPE
import sure

from util.streamlogger import InfoStreamLogger


rootLogger=logging.getLogger()

class TestGitAssumptions:
    """Test git features that we depend on"""

    def test_git_fetch_pack_for_unadvertised_sha(self):
        """in buildafi.git_origin_sha_is_pushed() we use git fetch-pack
           to query the origin remote for the existence of a 40-digit sha
           Unless the git remote server is configured to accept shas as arguments
           (see the end of `git fetch-pack --help` for details) the code we use
           could falsely indicate that a commit is not pushed in the AGFI metadata
        """

        """If this test starts failing, then we could fall back to a much slower
           implementation for is_pushed:
               # make sure all of the tracking branches are up to date locally
               # this could be really expensive if we intentionally do a shallow clone
               # of a really big repo for CI or builds
               git fetch --unshallow --prune origin
               upstream=$(git branch --contains {sha} --remote --list 'origin/*')
               is_pushed=$(test -n "$upstream") # it is pushed if the git-branch command prints branches
               # this also assumes that the upstream doesn't have tags that are unreachable
               # from branches. If you do, then finding things that are only reachable from tags
               # on the remote is a pain because tags get flattened into a single namespace
               # when they are fetched to local.  You would need to use git-ls-remote
               # to grab all refs that the remote knows about and then ask your local clone
               # whether the sha in question has anything not reachable from any of those
               # refs
               git ls-remote origin | awk '{print ^$1}' | xargs -x git rev-list {sha}
               # and if that outputs any revisions, they are not pushed and only reachable
               # from {sha}.  However, the -x is really important to notice because this
               # cmdline could get very long and has to be given as one cmd to git CLI
        """

        rootLogger.info("Ensure origin remote is defined and resolve it's fetch URL")
        url = run("git remote get-url origin", check=True, shell=True, stdout=PIPE, text=True).stdout.rstrip("\n")
        rootLogger.info(f"url:'{url}'")

        rootLogger.info("Ask origin for all 'advertised' objects")
        remote_refs = run("git ls-remote origin | awk '{print $1}'", shell=True, check=True, stdout=PIPE, text=True).stdout.rstrip("\n")
        remote_refs = set(remote_refs.split("\n"))
        remote_refs.should_not.be.empty
        rootLogger.info(f"found {len(remote_refs)} remote references")

        rootLogger.info("start from the default branch of the origin")
        sha = run(f"git ls-remote origin HEAD", text=True,
                     check=True, shell=True, stdout=PIPE).stdout.rstrip("\n").split()[0]
        rootLogger.info(f"starting sha:'{sha}'")

        while sha in remote_refs:
            # walk first parents backward in history
            # until we find one that isn't 'advertised' by ls-remote
            # will exit non-zero if we get to the end
            sha = run(f"git rev-parse {sha}~", text=True,
                      check=True, shell=True, stdout=PIPE).stdout.rstrip("\n")

        # now sha isn't in remote_refs
        rootLogger.info("Ask origin to send unadvertised commit that you know is reachable from there")
        with InfoStreamLogger('stdout'), InfoStreamLogger('stderr'):
            # if the server doesn't think 'sha' exists, then this will exit non-zero
            # that can only happen if the server is configured to disallow requesting
            # specific shas
            run(f"git fetch-pack --depth=1 {url} {sha}", check=True, shell=True)


class TestGitHelpers:
    """Test util/git.py functionality """

    def test_git_origin(self, temp_cloned_repo):
        from util.git import git_origin
        origin = git_origin(fspath(temp_cloned_repo))
        # this assertion is tightly coupled to how temp_cloned_repo fixture is setup in conftest.py
        origin.should.equal(fspath(temp_cloned_repo.parent / "origin"))


    def test_git_origin_undefined_returns_empty_string(self, temp_cloned_repo):
        from util.git import git_origin
        run(f"git -C {temp_cloned_repo} remote remove origin", shell=True, check=True)
        origin = git_origin(fspath(temp_cloned_repo))
        origin.should.equal("")


    def test_sha_dirty(self, temp_cloned_repo):
        from util.git import git_sha_dirty

        sha = []
        is_dirty = []
        rootLogger.info("temp_cloned_repo should initially be clean")
        s, d = git_sha_dirty(fspath(temp_cloned_repo))
        sha.append(s)
        is_dirty.append(d)
        sha[0].should.match(r'[0-9a-f]{40}', re.I)
        is_dirty[0].should.equal("")

        rootLogger.info("Modifying a file in temp_cloned_repo should make it dirty")
        fileb = temp_cloned_repo / "b.txt"
        fileb.write_text("I am modifying b.txt")
        s, d = git_sha_dirty(fspath(temp_cloned_repo))
        sha.append(s)
        is_dirty.append(d)
        sha[1].should.equal(sha[0])
        is_dirty[1].should.equal("-dirty")

        rootLogger.info("Committing my modifications should make it a new sha and clean again")
        run(f"git -C {temp_cloned_repo} commit -m 'a message' b.txt", shell=True, check=True)
        s, d = git_sha_dirty(fspath(temp_cloned_repo))
        sha.append(s)
        is_dirty.append(d)
        sha[2].should.match(r'[0-9a-f]{40}', re.I)
        sha[2].should_not.equal(sha[0])
        is_dirty[2].should.equal("")


    def test_git_is_pushed(self, temp_cloned_repo):
        from util.git import git_origin, git_server_do_you_have_this, git_sha_dirty, GitServerSHA1Denial
        from fabric.api import env

        # prevent fabric from raising SystemExit because error message is less useful
        env.abort_exception = Exception

        origin = git_origin(fspath(temp_cloned_repo))
        sha, dirty = git_sha_dirty(fspath(temp_cloned_repo))

        is_pushed = git_server_do_you_have_this(origin, sha, fspath(temp_cloned_repo))
        rootLogger.info("Initially cloned commit should exist on origin")
        is_pushed.should.equal(True)

        rootLogger.info("Making a new commit, it should not exist on origin")
        fileb = temp_cloned_repo / "b.txt"
        fileb.write_text("I am modifying b.txt")
        run(f"git -C {temp_cloned_repo} commit -m 'a message' b.txt", shell=True, check=True)
        sha, dirty = git_sha_dirty(fspath(temp_cloned_repo))
        is_pushed = git_server_do_you_have_this(origin, sha, fspath(temp_cloned_repo))
        is_pushed.should.equal(False)


    def test_git_is_pushed_throws_on_unexpected_error(self, temp_cloned_repo):
        from util.git import git_origin, git_server_do_you_have_this, git_sha_dirty
        from fabric.api import env

        origin = git_origin(fspath(temp_cloned_repo))
        sha, dirty = git_sha_dirty(fspath(temp_cloned_repo))

        expected_exception = env.abort_exception if env.abort_exception else SystemExit
        with pytest.raises(expected_exception) as exc:
            rootLogger.info("Calling get_server_do_you_have_this with invalid remote_url should raise")
            git_server_do_you_have_this(fspath(temp_cloned_repo.parent / "not_a_directory"),
                                        sha,
                                        fspath(temp_cloned_repo))
