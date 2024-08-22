import os

def extra_target_project_make_args(targetproject: str, deploydir: str) -> str:
    """Create extra make args for target projects that live outside FireSim.
    Eventually will be replaced by using YAML hooks.

    Args:
        targetproject: make arg corresponding to TARGET_PROJECT.
        deploydir: Deploy directory of FireSim

    Returns:
        any extra args needed for FireSim's make command to function
    """
    chipyard_dir = f"{deploydir}/../target-design/chipyard" if os.environ.get('FIRESIM_STANDALONE') else f"{deploydir}/../../.."
    if targetproject == "firesim":
        return f"TARGET_PROJECT_MAKEFRAG={chipyard_dir}/generators/firechip/chip/src/main/makefrag/{targetproject}"
    if targetproject == "bridges":
        return f"TARGET_PROJECT_MAKEFRAG={chipyard_dir}/generators/firechip/bridgestubs/src/main/makefrag/{targetproject}"
    return ""
