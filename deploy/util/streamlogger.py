"""See `StreamLogger`.

This is taken from https://gist.github.com/pmuller/2376336
which has no license associated with it.
"""

from __future__ import annotations

import sys
import logging
import io

from typing import Any, Optional, Tuple

# FIX (2026-07-08): StreamLogger intercepts sys.stdout/stderr and routes every
# line back through `logging`. If a logging emit ever fails (e.g. a disk-full
# write error on a long run, or an odd byte in streamed sim output),
# Handler.handleError() writes "--- Logging error ---" to sys.stderr — which is
# THIS intercepted stream — re-entering write()->flush()->logger()->emit()->
# handleError()-> ... until RecursionError kills the manager. Observed when a
# 641GB TracerV trace filled /scratch mid-run. Disabling logging.raiseExceptions
# makes handleError a no-op (the rare failing record is dropped, not recursed);
# combined with the reentrancy guard in flush() below. The uartlog is captured
# independently by `script`, so nothing important is lost.
logging.raiseExceptions = False


class StreamLogger:
    """
    A helper which intercepts what's written to an output stream
    then sends it, line by line, to a `logging.Logger` instance.
    Usage:
        By overwriting `sys.stdout`:
            sys.stdout = StreamLogger('stdout')
            print 'foo'
        As a context manager:
            with StreamLogger('stdout'):
                print 'foo'
    """

    __name: str
    __stream: Any
    __logger: Optional[logging.Logger]
    __buffer: io.StringIO
    __unbuffered: bool
    __flush_on_new_line: bool

    def __init__(
        self,
        name: str,
        logger: Optional[logging.Logger] = None,
        unbuffered: bool = False,
        flush_on_new_line: bool = True,
    ) -> None:
        """
        ``name``: The stream name to incercept ('stdout' or 'stderr')
        ``logger``: The logger that will receive what's written to the stream.
        ``unbuffered``: If `True`, `.flush()` will be called each time
                        `.write()` is called.
        ``flush_on_new_line``: If `True`, `.flush()` will be called each time
                               `.write()` is called with data containing a
                               new line character.
        """
        self.__name = name
        self.__stream = getattr(sys, name)
        self.__logger = logger or logging.getLogger()
        self.__buffer = io.StringIO()
        self.__unbuffered = unbuffered
        self.__flush_on_new_line = flush_on_new_line
        self.__reentrant = False

    def write(self, data: str) -> None:
        """Write data to the stream."""
        self.__buffer.write(data)
        if self.__unbuffered is True or (
            self.__flush_on_new_line is True and "\n" in data
        ):
            self.flush()

    def flush(self) -> None:
        """Flush the stream."""
        # Reentrancy guard: if the logger() call below fails and its handler's
        # handleError() writes to this same intercepted stream, we'd recurse
        # forever. When re-entered, dump straight to the real stream and return.
        if self.__reentrant:
            try:
                self.__stream.write(self.__buffer.getvalue())
                self.__stream.flush()
                self.__buffer.seek(0)
                self.__buffer.truncate()
            except Exception:
                pass
            return
        self.__reentrant = True
        try:
            self._flush_impl()
        finally:
            self.__reentrant = False

    def _flush_impl(self) -> None:
        self.__buffer.seek(0)
        while True:
            line = self.__buffer.readline()
            if line:
                if line[-1] == "\n":
                    line = line[:-1]
                    if line:
                        level, line = self.parse(line)
                        logger = getattr(self.__logger, level)
                        logger(line)
                else:
                    self.__buffer.seek(0)
                    self.__buffer.write(line)
                    self.__buffer.truncate()
                    break
            else:
                self.__buffer.seek(0)
                self.__buffer.truncate()
                break

    def parse(self, data: str) -> Tuple[str, str]:
        """Override me!"""
        return "debug", data

    def isatty(self) -> bool:
        """I'm not a tty."""
        return False

    def __enter__(self) -> None:
        """Enter the context manager."""
        setattr(sys, self.__name, self)

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        """Leave the context manager."""
        setattr(sys, self.__name, self.__stream)


class InfoStreamLogger(StreamLogger):
    """StreamLogger, but write to info log instead of debug."""

    def parse(self, data: str) -> Tuple[str, str]:
        return "info", data
