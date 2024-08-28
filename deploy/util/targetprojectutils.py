import os
from pathlib import Path
import logging

from typing import Optional

rootLogger = logging.getLogger()


def extra_target_project_make_args(
    targetproject: str, targetprojectmakefrag: Optional[str], deploydir: str
) -> str:
    """Create extra make args for target projects that live outside FireSim.
    Eventually will be replaced by using YAML hooks.

    Args:
        targetproject: make arg corresponding to TARGET_PROJECT.
        targetprojectmakefrag: optional make arg corresponding to TARGET_PROJECT (must be valid path).
        deploydir: Deploy directory of FireSim

    Returns:
        any extra args needed for FireSim's make command to function
    """
    if targetprojectmakefrag:
        p = Path(targetprojectmakefrag)
        if p.exists():
            rootLogger.debug(f"Using makefrag path given: {p}")
            return f"TARGET_PROJECT_MAKEFRAG={targetprojectmakefrag}"
        else:
            raise Exception(f"Invalid makefrag path given: {targetprojectmakefrag}")
    else:
        chipyard_dir = (
            f"{deploydir}/../../.."  # assumes firesim is a library inside of chipyard
        )
        rootLogger.debug(
            f"Having manager assume makefrag path from hardcoded Chipyard dir: {chipyard_dir}"
        )
        if targetproject == "firesim":
            return f"TARGET_PROJECT_MAKEFRAG={chipyard_dir}/generators/firechip/chip/src/main/makefrag/{targetproject}"
        if targetproject == "bridges":
            return f"TARGET_PROJECT_MAKEFRAG={chipyard_dir}/generators/firechip/bridgestubs/src/main/makefrag/{targetproject}"
        return ""


def resolve_path(path: str, base_file: str) -> Optional[str]:
    """Given a path (absolute or relative) and a base file, determine if the path can be found by itself.
    Otherwise, try to use the base file to determine the path (assuming it is a relative path).

    Args:
        path: path of file (relative or absolute)
        base_file: another file ("base file") whos directory path might be relative to

    Returns:
        either a resolved path or None
    """

    p_path = Path(path)
    if p_path.exists():
        return str(p_path.absolute())
    else:
        # search for the file relative to the directory of the base file
        p_parent_path = Path(base_file).absolute().parent
        p_abs_path: Path = p_parent_path / p_path
        if p_abs_path.exists():
            return str(p_abs_path.absolute())
        else:
            return None
