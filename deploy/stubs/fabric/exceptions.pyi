from typing import Any

class NetworkError(Exception):
    message: Any
    wrapped: Any
    def __init__(self, message: Any | None = ..., wrapped: Any | None = ...) -> None: ...

class CommandTimeout(Exception):
    timeout: Any
    message: Any
    def __init__(self, timeout) -> None: ...
