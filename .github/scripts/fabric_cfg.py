from fabric.api import env # type: ignore
import os

# Common fabric settings
env.output_prefix = False
env.abort_on_prompts = True
env.timeout = 100
env.connection_attempts = 10
env.disable_known_hosts = True
env.keepalive = 60 # keep long SSH connections running

def setup_shell_env_vars():
    # if the following env. vars exist, then propagate to fabric subprocess
    shell_env_vars = {
        "TEST_DISABLE_VERILATOR",
        "TEST_DISABLE_VIVADO",
        "TEST_DISABLE_BENCHMARKS",
    }
    export_shell_env_vars = set()
    for v in shell_env_vars:
        if v in os.environ:
            export_shell_env_vars.add(f"{v}={os.environ[v]}")

    return ("export " + " ".join(export_shell_env_vars)) if export_shell_env_vars else "true"
