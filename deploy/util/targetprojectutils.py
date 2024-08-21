def extra_target_project_make_args(targetproject: str, deploydir: str) -> str:
    """Create extra make args for target projects that live outside FireSim.
    Eventually will be replaced by using YAML hooks.

    Args:
        targetproject: make arg corresponding to TARGET_PROJECT.
        deploydir: Deploy directory of FireSim

    Returns:
        any extra args needed for FireSim's make command to function
    """
    if targetproject == "firesim":
        return f"TARGET_PROJECT_MAKEFRAG={deploydir}/../target-design/chipyard/generators/firechip/chip/src/main/makefrag/{targetproject}"
    if targetproject == "bridges":
        return f"TARGET_PROJECT_MAKEFRAG={deploydir}/../target-design/chipyard/generators/firechip/bridgestubs/src/main/makefrag/{targetproject}"
    return ""
