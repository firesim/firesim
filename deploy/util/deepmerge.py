from __future__ import annotations

from copy import deepcopy

# imports needed for python type checking
from typing import List

# from https://gist.github.com/angstwad/bf22d1822c38a92ec0a9
def deep_merge(a: dict, b: dict) -> dict:
    """Merge two dicts and return a singular dict"""
    result = deepcopy(a)
    for bk, bv in b.items():
        av = result.get(bk)
        if isinstance(av, dict) and isinstance(bv, dict):
            result[bk] = deep_merge(av, bv)
        else:
            result[bk] = deepcopy(bv)
    return result
