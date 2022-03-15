from typing import Any

class ThreadHandler:
    exception: Any
    thread: Any
    def __init__(self, name, callable, *args, **kwargs) -> None: ...
    def raise_if_needed(self) -> None: ...
