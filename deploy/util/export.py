import os

from typing import Set


def create_export_string(vars: Set[str]) -> str:
    """For v in vars, if v exists, then add it to an export string"""
    export_shell_env_vars = set()
    for v in vars:
        if v in os.environ:
            export_shell_env_vars.add(f"{v}={os.environ[v]}")
    return (
        ("export " + " ".join(export_shell_env_vars))
        if export_shell_env_vars
        else "true"
    )
