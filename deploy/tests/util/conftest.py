import pytest
from subprocess import run, CompletedProcess
import sure


def shell(cmd:str) -> CompletedProcess:
    return run(cmd, check=True, shell=True, capture_output=True, text=True)

@pytest.fixture()
def git_config_only_local(monkeypatch):
    """Prevent git from being influenced by ~/.gitconfig or $PREFIX/etc/gitconfig

       see:
         * https://git-scm.com/docs/git#Documentation/git.txt-codeGITCONFIGGLOBALcode
         * https://docs.pytest.org/en/7.0.x/how-to/monkeypatch.html#monkeypatching-environment-variables
    """
    monkeypatch.setenv("GIT_CONFIG_GLOBAL", "/dev/null")
    monkeypatch.setenv("GIT_CONFIG_SYSTEM", "/dev/null")

@pytest.fixture()
def temp_cloned_repo(tmp_path, git_config_only_local):
    origin = tmp_path / "origin"
    origin.mkdir()
    shell(f"git -C {origin} init ")
    oa = origin / "a.txt"
    oa.write_text("This is a file")
    ob = origin / "b.txt"
    ob.write_text("This is file b")
    shell(f"git -C {origin} add .")
    shell(f"git -C {origin} commit -m initial")

    clone = tmp_path / "clone"
    shell(f"git -C {tmp_path} clone origin clone")
    clone.exists().should.equal(True)

    oc = origin / "c.txt"
    oc.write_text("This file won't be in the clone.")
    shell(f"git -C {origin} add c.txt")
    shell(f"git -C {origin} commit -m 'adding c.txt'")
    yield clone

