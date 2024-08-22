import os
from pathlib import Path
import logging

from typing import Optional

rootLogger = logging.getLogger()

def extra_target_project_make_args(targetproject: str, targetprojectmakefrag: Optional[str], deploydir: str) -> str:
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
        chipyard_dir = f"{deploydir}/../../.." # assumes firesim is a library inside of chipyard
        rootLogger.debug(f"Having manager assume makefrag path from hardcoded Chipyard dir: {chipyard_dir}")
        if targetproject == "firesim":
            return f"TARGET_PROJECT_MAKEFRAG={chipyard_dir}/generators/firechip/chip/src/main/makefrag/{targetproject}"
        if targetproject == "bridges":
            return f"TARGET_PROJECT_MAKEFRAG={chipyard_dir}/generators/firechip/bridgestubs/src/main/makefrag/{targetproject}"
        return ""
