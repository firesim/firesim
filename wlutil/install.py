"""Install a FireMarshal config to an external tool. Installers are provided
by the boards as a python module in board-dir/installers."""
import importlib
import sys
from . import wlutil


def installWorkload(cfg, installer='firesim'):
    installerPath = wlutil.getOpt('installers') / installer

    if not installerPath.exists():
        raise NotImplementedError("The requested installation target (" + installer + ") is not supported on this board")

    spec = importlib.util.spec_from_file_location(installer, installerPath / '__init__.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)

    module.install(cfg, wlutil.getCtx())
