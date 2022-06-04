from __future__ import annotations

# imports needed for python type checking
from typing import List

def file_line_swap(in_file: str, out_file: str, start_marker: str, end_marker: str, inject_lines: List[str]):
    """Inject a set of lines into a file given two markers. Output into a new file.

    Ex.
        In:
            Temp
            # in mark
            Temp2
            # out mark
            Temp3

        Run w/ start_marker = "in" and out_marker = "out" and inject_lines = [Hi\n, I'm, Jeremy]

        Out:
            Temp
            Hi
            I'mJeremy
            Temp3

    Args:
        in_file: input file to use as a base
        out_file: output file to write into
        start_marker: use this to determine start of replace area
        end_marker: use this to determine end of replace area
        inject_lines: lines to inject in the replacement area
    """
    with open(in_file, "r") as f:
        og_lines = f.readlines()

    start_count = 0
    end_count = 0
    for og_line in og_lines:
        if start_marker in og_line:
            start_count += 1
        if end_marker in og_line:
            end_count += 1

    assert start_count == 1, f"""Found {start_count} occurrences of "{start_marker}" in {in_file}. Only 1 allowed."""
    assert end_count == 1, f"""Found {end_count} occurrences of "{end_marker}" in {in_file}. Only 1 allowed."""

    with open(out_file, "w") as f:
        write_og = True
        written = False
        for og_line in og_lines:
            if start_marker in og_line:
                write_og = False

            if write_og:
                f.write(og_line)
            else:
                if not written:
                    f.writelines(inject_lines)
                    written = True

            if end_marker in og_line:
                write_og = True
