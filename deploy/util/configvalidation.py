from __future__ import annotations

import yamale # type: ignore

from typing import Optional, List

def validate(src_yaml: str, schema_yaml: str, extra_schema_yaml: Optional[str] = None, ignore_statements: List[str] = [], src_as_str: bool = False) -> bool:
    """Validate a src yaml file with a schema file (or extra one)

    Args:
        src_yaml: path to yaml file (default) or yaml string to check
        schema_yaml: path to schema yaml file to use for check
        extra_schema_yaml: path to another schema yaml file to use for check
        ignore_statements: if an error message matches any string in the list of strings then ignore that error
        src_as_str: treat src_yaml input as a yaml string instead of file

    Returns:
        Boolean indicating if validation was successful
    """
    schema = yamale.make_schema(schema_yaml)
    if src_as_str:
        data = yamale.make_data(content=src_yaml)
    else:
        data = yamale.make_data(src_yaml)

    if extra_schema_yaml:
        raw_schemas = yamale.readers.parse_yaml(extra_schema_yaml)
        if not raw_schemas:
            raise ValueError(f'{extra_schema_yaml} is an empty file!')

        try:
            for raw_schema in raw_schemas:
                schema.add_include(raw_schema)
        except (TypeError, SyntaxError) as e:
            raise SyntaxError(f'Schema error in file {extra_schema_yaml}\n{e}')

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
            if src_as_str:
                print(f"Error validating YAML string:\n{src_yaml}\nwith {schema_yaml} and {extra_schema_yaml}:")
            else:
                print(f"Error validating {src_yaml} with {schema_yaml} and {extra_schema_yaml}:")
            for error in filtered_errors:
                print(f'\t{error}')
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
