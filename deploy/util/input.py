"""
See 'streamlogger.py' and it's use at the end of 'firesim.py'
"""

import logging

def firesim_input(prompt=None):
    """wrap __builtin__.input() understanding the idiocyncracies of firesim+fabric+logging

    Log the prompt at CRITICAL level so that it will go to the terminal and the log.
    Log the entered text as DEBUG so that the log contains it.
    Don't pass the prompt to __builtin__.input() because we don't need StreamLogger to also
    be trying to log the prompt.
    """

    rootLogger = logging.getLogger()
    if prompt:
	rootLogger.critical(prompt)

    res = input()
    rootLogger.debug("User Provided input():%s", res)

    return res



