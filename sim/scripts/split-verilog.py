#!/usr/bin/env python3
"""
Split a single SystemVerilog file into separate files, one per module.

This script parses a SystemVerilog file and extracts each module definition
into its own file, similar to what CIRCT firtool --split-verilog does.

Usage:
    python3 split-verilog.py <input.sv> -o <output_dir> [--filelist <filelist.f>]
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import List, Tuple, Optional


class ModuleExtractor:
    """Extracts modules from a SystemVerilog file."""

    def __init__(self, content: str):
        self.content = content
        self.lines = content.split('\n')
        self.modules: List[Tuple[str, int, int, str]] = []  # (name, start_line, end_line, content)
        self.global_content: List[str] = []  # Content before first module
        self.includes: List[str] = []  # `include statements

    def extract_modules(self):
        """Extract all module definitions from the file.
        
        Simple approach: find lines with "module <name> (" and "endmodule"
        """
        i = 0
        module_start = None
        module_name = None
        current_module_lines: List[str] = []

        # Extract global includes and declarations
        while i < len(self.lines):
            line = self.lines[i]
            stripped = line.strip()

            # Collect `include statements
            if stripped.startswith('`include'):
                self.includes.append(line)
                i += 1
                continue

            # Look for module declaration: "module <name>" 
            # SystemVerilog can have:
            #   - module name (ports);
            #   - module name (ports)
            #   - module name;
            #   - module name (multi-line)
            # Match pattern: module followed by whitespace, then module name
            # Must be at start of line (or with leading whitespace) and not in a comment
            if stripped.startswith('//') or stripped.startswith('/*'):
                # Skip comment lines
                if module_start is not None:
                    current_module_lines.append(line)
                i += 1
                continue
                
            module_match = re.match(r'^\s*module\s+(\w+)', line)
            if module_match:
                # If we were already in a module, save it first
                if module_start is not None:
                    module_content = '\n'.join(current_module_lines)
                    self.modules.append((module_name, module_start, i - 1, module_content))
                    print(f"Warning: Module {module_name} ended at line {i-1} without explicit endmodule", file=sys.stderr)
                
                # Start new module
                module_name = module_match.group(1)
                module_start = i
                current_module_lines = [line]
                i += 1
                continue

            # Look for endmodule
            if re.search(r'\bendmodule\b', line):
                if module_start is not None:
                    # End of current module
                    current_module_lines.append(line)
                    module_content = '\n'.join(current_module_lines)
                    self.modules.append((module_name, module_start, i, module_content))
                    module_start = None
                    module_name = None
                    current_module_lines = []
                else:
                    # Orphaned endmodule
                    print(f"Warning: endmodule found at line {i+1} without matching module", file=sys.stderr)
                i += 1
                continue

            # If we're inside a module, collect the line
            if module_start is not None:
                current_module_lines.append(line)
            else:
                # Global content (includes, defines, etc.)
                if stripped and not stripped.startswith('//') and not stripped.startswith('/*'):
                    self.global_content.append(line)
            
            i += 1

        # Handle unclosed module at end of file
        if module_start is not None:
            module_content = '\n'.join(current_module_lines)
            self.modules.append((module_name, module_start, len(self.lines) - 1, module_content))
            print(f"Warning: Module {module_name} was not properly closed with endmodule", file=sys.stderr)


def sanitize_filename(name: str) -> str:
    """Convert module name to a safe filename."""
    # Replace any problematic characters
    return re.sub(r'[^\w\-_\.]', '_', name)


def write_module_file(output_dir: Path, module_name: str, content: str, includes: List[str]):
    """Write a module to its own file."""
    filename = sanitize_filename(module_name) + '.sv'
    filepath = output_dir / filename

    with open(filepath, 'w') as f:
        # Write includes first
        if includes:
            f.write('\n'.join(includes))
            f.write('\n\n')
        # Write module content
        f.write(content)
        f.write('\n')

    return filename


def write_filelist(output_dir: Path, modules: List[str], filelist_path: Optional[Path] = None):
    """Write a filelist of all generated module files."""
    if filelist_path is None:
        filelist_path = output_dir / 'modules.f'

    with open(filelist_path, 'w') as f:
        for module_file in sorted(modules):
            # Use relative path from output_dir
            rel_path = os.path.relpath(output_dir / module_file, output_dir)
            f.write(f"{rel_path}\n")

    return filelist_path


def main():
    parser = argparse.ArgumentParser(
        description='Split a SystemVerilog file into separate module files',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Split FireSim-generated.sv into separate files
  python3 split-verilog.py FireSim-generated.sv -o verilog_modules/

  # Also generate a filelist
  python3 split-verilog.py FireSim-generated.sv -o verilog_modules/ --filelist modules.f
        """
    )
    parser.add_argument('input_file', help='Input SystemVerilog file to split')
    parser.add_argument('-o', '--output-dir', required=True,
                        help='Output directory for split module files')
    parser.add_argument('--filelist', default=None,
                        help='Output filelist path (default: <output-dir>/modules.f)')
    parser.add_argument('--preserve-global', action='store_true',
                        help='Preserve global content (defines, etc.) in each module file')

    args = parser.parse_args()

    # Read input file
    input_path = Path(args.input_file)
    if not input_path.exists():
        print(f"Error: Input file '{input_path}' does not exist", file=sys.stderr)
        sys.exit(1)

    with open(input_path, 'r') as f:
        content = f.read()

    # Create output directory
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Extract modules
    extractor = ModuleExtractor(content)
    extractor.extract_modules()

    if not extractor.modules:
        print("Warning: No modules found in input file", file=sys.stderr)
        sys.exit(1)

    print(f"Found {len(extractor.modules)} modules", file=sys.stderr)

    # Write each module to its own file
    module_files = []
    seen_modules = set()
    for module_name, start_line, end_line, module_content in extractor.modules:
        # Check for duplicate module names (might indicate parsing issue)
        if module_name in seen_modules:
            print(f"Warning: Duplicate module name '{module_name}' found. Appending suffix.", file=sys.stderr)
            suffix = 1
            orig_name = module_name
            while module_name in seen_modules:
                module_name = f"{orig_name}_{suffix}"
                suffix += 1
        seen_modules.add(module_name)
        
        # Verify this module content actually contains only one module
        module_count = len(re.findall(r'\bmodule\s+\w+', module_content))
        endmodule_count = len(re.findall(r'\bendmodule\b', module_content))
        if module_count > 1 or endmodule_count > 1:
            print(f"Warning: Module {module_name} appears to contain multiple modules (module: {module_count}, endmodule: {endmodule_count})", file=sys.stderr)
        
        # Prepend includes and global content if requested
        full_content = module_content
        if args.preserve_global:
            if extractor.includes:
                full_content = '\n'.join(extractor.includes) + '\n\n' + full_content
            if extractor.global_content:
                full_content = '\n'.join(extractor.global_content) + '\n\n' + full_content

        filename = write_module_file(output_dir, module_name, full_content, extractor.includes)
        module_files.append(filename)
        print(f"  {module_name} -> {filename} ({end_line - start_line + 1} lines, module count: {module_count}, endmodule count: {endmodule_count})", file=sys.stderr)

    # Write filelist
    filelist_path = write_filelist(output_dir, module_files, Path(args.filelist) if args.filelist else None)
    print(f"\nFilelist written to: {filelist_path}", file=sys.stderr)
    print(f"Total modules: {len(module_files)}", file=sys.stderr)



if __name__ == '__main__':
    main()

