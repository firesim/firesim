from fabric.auth import get_password as get_password, set_password as set_password
from fabric.exceptions import CommandTimeout as CommandTimeout
from fabric.network import normalize as normalize#, ssh as ssh
from fabric.state import env as env, output as output, win32 as win32
from typing import Any, IO
from paramiko import Channel

def output_loop(*args, **kwargs) -> None: ...

class OutputLooper:
    chan: Channel
    stream: Any
    capture: Any
    timeout: Any
    read_func: Any
    prefix: str
    printing: str
    linewise: Any
    reprompt: bool
    read_size: int
    write_buffer: Any
    def __init__(self, chan, attr, stream, capture, timeout) -> None: ...
    def loop(self) -> None: ...
    def prompt(self) -> None: ...
    def try_again(self) -> None: ...

# f is maybe TextIO only but not positive
def input_loop(chan: Channel, f: IO, using_pty: Any) -> None: ...
