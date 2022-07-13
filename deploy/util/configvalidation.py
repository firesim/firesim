from __future__ import annotations

import yamale # type: ignore

from typing import Optional, List

def validate(src_yaml_path: str = None, src_yaml_str: str = None, schema_yaml_path: str = None, extra_schema_yaml_path: Optional[str] = None, ignore_statements: List[str] = []) -> bool:
    """Validate a src yaml file with a schema file (or extra one)

    Args:
        src_yaml_path: path to yaml file to check (cannot be used with src_yaml_str)
        src_yaml_str: yaml string to check (cannot be used with src_yaml_path)
        schema_yaml_path: path to schema yaml file to use for check
        extra_schema_yaml_path: path to another schema yaml file to use for check
        ignore_statements: if an error message matches any string in the list of strings then ignore that error

    Returns:
        Boolean indicating if validation was successful
    """
    if (src_yaml_path is None and src_yaml_str is None) or (src_yaml_path is not None and src_yaml_str is not None):
        raise Exception("Pass either src_yaml_path= or src_yaml_str=, not both")

    schema = yamale.make_schema(schema_yaml_path)
    if src_yaml_path:
        data = yamale.make_data(src_yaml_path)
    else:
        data = yamale.make_data(content=src_yaml_str)

    if extra_schema_yaml_path:
        raw_schemas = yamale.readers.parse_yaml(extra_schema_yaml_path)
        if not raw_schemas:
            raise ValueError(f'{extra_schema_yaml_path} is an empty file!')

        try:
            for raw_schema in raw_schemas:
                schema.add_include(raw_schema)
        except (TypeError, SyntaxError) as e:
            raise SyntaxError(f'Schema error in file {extra_schema_yaml_path}\n{e}')

    errors = validate_wrapper(schema, data)
    if errors is None:
        return True
    else:
        def filter_cond(error: str):
            for stmt in ignore_statements:
                if stmt in error:
                    return False
            return True
        filtered_errors = list(filter(filter_cond, errors) )
        if filtered_errors:
            all_schema_files = [schema_yaml_path] + ([extra_schema_yaml_path] if extra_schema_yaml_path else [])
            if src_yaml_str:
                print(f"::ERROR:: Unable to validate following YAML snippet with schema(s) ({all_schema_files}):\n{src_yaml_str.strip()}")
            else:
                print(f"::ERROR:: Unable to validate {src_yaml_path} (source yaml) with schema(s) ({all_schema_files}).")
            for error in filtered_errors:
                print(f'::ERROR:: Mismatch:    {error}')
            return False
        else:
            return True

def validate_wrapper(schema, data):
    try:
        yamale.validate(schema, data)
        return None
    except yamale.YamaleError as e:
        all_errors = []
        for result in e.results:
            all_errors.append(result.errors)
        return result.errors
