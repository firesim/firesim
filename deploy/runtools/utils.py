""" Miscellaneous utils used by other runtools pieces. """

from __future__ import annotations

import sys
import lddwrap
import logging
from os import fspath
from os.path import realpath
from pathlib import Path
from fabric.api import run, warn_only, hide, get, local, settings  # type: ignore
import hashlib
from tempfile import TemporaryDirectory

from awstools.awstools import get_localhost_instance_id
from buildtools.bitbuilder import get_deploy_dir

from typing import List, Tuple, Type, Optional

rootLogger = logging.getLogger()


def has_sudo() -> bool:
    with warn_only(), hide("warnings"):
        return run("sudo -ln true").return_code == 0


def get_local_shared_libraries(elf: str) -> List[Tuple[str, str]]:
    """Given path to executable `exe`, returns a list of path tuples, (A, B), where:
    A is the local file path on the manager instance to the library
    B is the destination file path on the run farm instance relative to the driver

    NOTE: ignores the following dso's reported by ldd:
     * linux-vdso : special dso injected by the kernel, not copyable
     * ld-linux : is the dynamic loader, not copyable
     * known members of glibc.  These could be copyable but
       glibc is very coupled to the kernel version and following the pattern
       of the conda packages we build from, we will not copy glibc around.
       We compile against glibc and given the backwards compatibility of glibc
       should be able to copy everything else to most other hosts
       and they will work from a DSO linker/loader perspective (i.e.
       if you're building a driver for AWS, it doesn't magically work
       on a different platform just because you have libraries that will
       link and load)
    """

    os_flavor = local(
        "grep '^ID=' /etc/os-release | awk -F= '{print $2}' | tr -d '\"'", capture=True
    )
    rootLogger.debug(f"Running on OS: {os_flavor}")

    if os_flavor not in ["ubuntu", "centos", "amzn", "debian"]:
        raise ValueError(f"Unknown OS: {os_flavor}")

    glibc_shared_libs = []
    if os_flavor in ["ubuntu", "debian"]:
        lines = []
        with settings(warn_only=True):
            dpkg_output = local(
                "dpkg -S /usr/lib/x86_64-linux-gnu/libc.so*", capture=True
            )
            if dpkg_output.return_code == 1:
                print(f"Warning got:\n{dpkg_output.stderr}")
            lines = dpkg_output.split("\n")
            pkgs = [":".join(l.split(":")[:1]) for l in lines]

        rootLogger.debug(pkgs)

        for pkg in pkgs:
            dpkg_output_paths = local(
                f"dpkg -L {pkg} | grep -P '\.so(\.|\s*$)'", capture=True
            )
            lines = dpkg_output.split("\n")
            glibc_shared_libs.extend(dpkg_output_paths.stdout.split("\n"))

        rootLogger.debug(glibc_shared_libs)
    elif os_flavor in ["centos", "amzn"]:
        with settings(warn_only=True):
            rpm_output = local(
                "rpm -q -f /lib64/libc.so* --filesbypkg | grep -P '\.so(\.|\s*$)'"
            )
            if rpm_output.return_code == 1:
                print(f"Warning got:\n{rpm_output.stderr}")
            glibc_shared_libs.extend(rpm_output.split("\n"))

    libs = []
    rootLogger.debug(f"Identifying ldd dependencies for: {elf}")
    for dso in lddwrap.list_dependencies(Path(elf)):
        if dso.soname is None:
            assert dso.path is not None and "/ld-linux" in fspath(
                dso.path
            ), f"dynamic linker is only allowed no soname, not: {dso}"
            continue
        if "linux-vdso.so" in dso.soname:
            continue
        assert (
            dso.path is not None
        ), f"{dso.soname} has no linkage reported by ldd for {elf} in: {dso}"
        if fspath(dso.path) in glibc_shared_libs:
            rootLogger.debug(f"{dso.path} found in glibc_shared_libs. skipping")
            continue
        if fspath(realpath(dso.path)) in glibc_shared_libs:
            rootLogger.debug(
                f"{dso.path} realpath {realpath(dso.path)} found in glibc_shared_libs. skipping"
            )
            continue

        # we do some moderate evilness here and collapse
        # the version symlinks of our dso into the actual dso
        # so that we have fewer pieces to copy over
        libs.append((realpath(dso.path), dso.soname))

    rootLogger.debug(libs)

    return libs


class MacAddress:
    """This class allows globally allocating/assigning MAC addresses.
    It also wraps up the code necessary to get a string version of a mac
    address. The MAC prefix we use here is 00:12:6D, which is assigned to the
    EECS Department at UC Berkeley.

    >>> MacAddress.reset_allocator()
    >>> mac = MacAddress()
    >>> str(mac)
    '00:12:6D:00:00:02'
    >>> mac.as_int_no_prefix()
    2
    >>> mac = MacAddress()
    >>> mac.as_int_no_prefix()
    3
    """

    next_mac_alloc: int = 2
    eecs_mac_prefix: int = 0x00126D000000
    mac_without_prefix_as_int: int
    mac_as_int: int

    def __init__(self) -> None:
        """Allocate a new mac address, store it, then increment nextmacalloc."""
        assert MacAddress.next_mac_alloc < 2**24, "Too many MAC addresses allocated"
        self.mac_without_prefix_as_int = MacAddress.next_mac_alloc
        self.mac_as_int = MacAddress.eecs_mac_prefix + MacAddress.next_mac_alloc

        # increment for next call
        MacAddress.next_mac_alloc += 1

    def as_int_no_prefix(self) -> int:
        """Return the MAC address as an int. WITHOUT THE PREFIX!
        Used by the MAC tables in switch models."""
        return self.mac_without_prefix_as_int

    def __str__(self) -> str:
        """Return the MAC address in the "regular format": colon separated,
        show all leading zeroes."""
        # format as 12 char hex with leading zeroes
        str_ver = format(self.mac_as_int, "012X")
        # split into two hex char chunks
        import re

        split_str_ver = re.findall("..?", str_ver)
        # insert colons
        return ":".join(split_str_ver)

    @classmethod
    def reset_allocator(cls: Type[MacAddress]) -> None:
        """Reset allocator back to default value."""
        cls.next_mac_alloc = 2

    @classmethod
    def next_mac_to_allocate(cls: Type[MacAddress]) -> int:
        """Return the next mac that will be allocated. This basically tells you
        how many entries you need in your switching tables."""
        return cls.next_mac_alloc


def is_on_aws() -> bool:
    return get_localhost_instance_id() is not None


def run_only_aws(*args, **kwargs) -> None:
    """Enforce that the Fabric run command is only run on AWS."""
    if is_on_aws():
        run(*args, **kwargs)
    else:
        sys.exit(1)


def get_md5(file):
    """For a local file, get the md5 hash as a string."""
    return hashlib.md5(open(file, "rb").read()).hexdigest()


# firesim scripts that require sudo access are stored here
# must be updated at the same time as the documentation/installation instructions
script_path = Path("/usr/local/bin")


def check_script(remote_script_str: str, search_dir: Optional[Path] = None) -> None:
    """Given a remote_script (absolute or relative path), search for the
    script in a known location (based on it's name) in the local FireSim
    repo and compare it's contents to ensure they are the same.
    """
    if search_dir is None:
        search_dir = Path(f"{get_deploy_dir()}/sudo-scripts")

    remote_script = Path(remote_script_str)

    with TemporaryDirectory() as tmp_dir:
        if remote_script.is_absolute():
            r = remote_script
        else:
            r = run(f"which {remote_script}")
        get(str(r), tmp_dir)
        local_script = f"{search_dir}/{remote_script.name}"
        if get_md5(local_script) != get_md5(f"{tmp_dir}/{remote_script.name}"):
            raise Exception(
                f"""{remote_script} (on remote) differs from the current FireSim version of {local_script}. Ensure the proper FireSim scripts are sourced (and are the same version as this FireSim)"""
            )
            sys.exit(1)
