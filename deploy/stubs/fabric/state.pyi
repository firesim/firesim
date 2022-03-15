from fabric.network import HostConnectionCache as HostConnectionCache#, ssh as ssh
from fabric.version import get_version as get_version
from typing import List, Any, Dict, Hashable, Callable
from paramiko import Channel

win32: bool
default_port: str
default_ssh_config_path: str
env_options: List[Any] # really of optparse.make_option 
env: Any # fabric.utils._AttributeDict
exceptions: List[str]
exception_dict: dict
commands: Dict[Hashable, Callable]
connections: HostConnectionCache

def default_channel() -> Channel: ...

output: Any # fabric.utils._AliasDict
