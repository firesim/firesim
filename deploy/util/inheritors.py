from __future__ import annotations

# imports needed for python type checking
from typing import Set, Type, Any

def inheritors(klass: Type[Any]) -> Set[Type[Any]]:
    """Determine the subclasses that inherit from the input class.
    This is taken from https://stackoverflow.com/questions/5881873/python-find-all-classes-which-inherit-from-this-one.

    Args:
        klass: Input class.

    Returns:
        Set of subclasses that inherit from input class.
    """
    subclasses = set()
    work = [klass]
    while work:
        parent = work.pop()
        for child in parent.__subclasses__():
            if child not in subclasses:
                subclasses.add(child)
                work.append(child)
    return subclasses
