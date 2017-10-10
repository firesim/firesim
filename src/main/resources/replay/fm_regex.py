import re

# See LICENSE for license details.

""" define reference(RTL) name regular expression """
ref_regex = re.compile(r"""
  Ref\s+                             # is reference(RTL)?
  (DFF|BlPin|Port|BBox|BBPin)\w*\s+  # Type
  (?:[\w\(\)]*)\s                    # Matched by (e.g. name)
  r:/WORK/                           # name prefix
  ([\w/\[\]\$]*)                     # RTL(chisel) name
 """, re.VERBOSE)

""" define implemntation(gate-level designs) name regular expression """
impl_regex = re.compile(r"""
  Impl\s+                               # is implementation(gate-level design)?
  (?:DFF|BlPin|Port|BBox|BBPin)\w*\s+   # Type
  (?:\(-\))?\s+                         # Inverted?
  (?:[\w\(\)]*)\s                       # Matched by (e.g. name)
  i:/WORK/                              # name prefix
  ([\w/\[\]]*)                          # gate-level name
 """, re.VERBOSE)

ff_regex = re.compile(r"([\w.\$]*)_reg")
reg_regex = re.compile(r"([\w.\$]*)_reg[_\[](\d+)[_\]]")
mem_regex = re.compile(r"([\w.\$]*)_reg[_\[](\d+)[_\]][_\[](\d+)[_\]]")
bus_regex = re.compile(r"([\w.\$]*)[_\[](\d+)[_\]]")
