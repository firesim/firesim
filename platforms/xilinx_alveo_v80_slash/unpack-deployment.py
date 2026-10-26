#!/usr/bin/env python3
# See LICENSE for license details.
"""Validate and unpack an accepted SLASH deployment without overwriting history."""
import argparse
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
from deployment import require, sha, verify_bundle

PLATFORM = "xilinx_alveo_v80_slash"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    destination = args.destination.resolve(strict=True)
    target = destination / PLATFORM
    temporary = Path(tempfile.mkdtemp(prefix=".slash-deployment-", dir=destination))
    try:
        with tarfile.open(args.archive, "r:gz") as archive:
            members = archive.getmembers()
            names = set()
            for member in members:
                path = Path(member.name)
                require(
                    not path.is_absolute()
                    and ".." not in path.parts
                    and path.as_posix() == member.name.rstrip("/")
                    and path.parts[0] == PLATFORM
                    and (member.isdir() or member.isfile())
                    and path.as_posix() not in names,
                    "Unsafe or duplicate deployment member",
                )
                names.add(path.as_posix())
            # Only directories and regular files survived; no links or devices.
            for member in members:
                path = temporary / member.name
                if member.isdir():
                    path.mkdir(parents=True, exist_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with archive.extractfile(member) as source, path.open(
                        "xb"
                    ) as output:
                        shutil.copyfileobj(source, output)
                    path.chmod(member.mode & 0o777)
        staged = temporary / PLATFORM
        verify_bundle(staged)
        if target.exists():
            verify_bundle(target)
            require(
                sha(target / "deployment.json") == sha(staged / "deployment.json"),
                "Another deployment already occupies this slot; use a new simulation directory",
            )
        else:
            os.rename(staged, target)
        print(target)
    finally:
        shutil.rmtree(temporary)


if __name__ == "__main__":
    main()
