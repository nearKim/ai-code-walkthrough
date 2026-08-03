import ast
import json
import os
import sys
from typing import Any, Optional, Union

IGNORED_DIRECTORIES = {
    ".git",
    ".hg",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".tox",
    ".venv",
    "__pycache__",
    "build",
    "dist",
    "node_modules",
    "venv",
}
MAX_FILES = 180
MAX_SYMBOLS = 1_200
MAX_FIELDS = 24
MAX_FILE_BYTES = 512_000


def expression(node: Optional[ast.AST]) -> Optional[str]:
    if node is None:
        return None
    try:
        return ast.unparse(node)[:160]
    except (RecursionError, ValueError):
        return getattr(node, "id", None) or getattr(node, "attr", None)


def target_fields(node: ast.AST) -> list[str]:
    if isinstance(node, (ast.Tuple, ast.List)):
        return [field for item in node.elts for field in target_fields(item)]
    if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id in {"self", "cls"}:
        return [node.attr]
    return []


class StateWriteVisitor(ast.NodeVisitor):
    def __init__(self) -> None:
        self.state_writes: set[str] = set()

    def visit_Assign(self, node: ast.Assign) -> None:
        for target in node.targets:
            self.state_writes.update(target_fields(target))
        self.visit(node.value)

    def visit_AnnAssign(self, node: ast.AnnAssign) -> None:
        self.state_writes.update(target_fields(node.target))
        if node.value is not None:
            self.visit(node.value)

    def visit_AugAssign(self, node: ast.AugAssign) -> None:
        self.state_writes.update(target_fields(node.target))
        self.visit(node.value)

    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        return

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
        return

    def visit_ClassDef(self, node: ast.ClassDef) -> None:
        return


def function_info(node: Union[ast.FunctionDef, ast.AsyncFunctionDef]) -> tuple[dict[str, Any], set[str]]:
    visitor = StateWriteVisitor()
    for statement in node.body:
        visitor.visit(statement)
    return {
        "n": node.name,
        "r": [node.lineno, getattr(node, "end_lineno", node.lineno)],
    }, visitor.state_writes


def class_fields(node: ast.ClassDef) -> set[str]:
    fields: set[str] = set()
    for statement in node.body:
        if isinstance(statement, (ast.Assign, ast.AnnAssign)):
            targets = statement.targets if isinstance(statement, ast.Assign) else [statement.target]
            for target in targets:
                if isinstance(target, ast.Name):
                    fields.add(target.id)
    return fields


def class_info(node: ast.ClassDef) -> dict[str, Any]:
    methods = []
    fields = class_fields(node)
    for item in node.body:
        if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
            method, state_writes = function_info(item)
            methods.append(method)
            fields.update(state_writes)
    return {
        "n": node.name,
        "r": [node.lineno, getattr(node, "end_lineno", node.lineno)],
        "b": [value for value in (expression(item) for item in node.bases) if value],
        "s": sorted(fields)[:MAX_FIELDS],
        "m": methods,
    }


def module_imports(tree: ast.Module) -> list[str]:
    imports: set[str] = set()
    for node in tree.body:
        if isinstance(node, ast.Import):
            imports.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            module = "." * node.level + (node.module or "")
            imports.add(module)
    return sorted(imports)


def is_test_file(relative_path: str) -> bool:
    parts = relative_path.split("/")
    return parts[-1].startswith("test_") or any(part in {"test", "tests"} for part in parts)


def python_files(root: str) -> list[tuple[str, str]]:
    paths = []
    for directory, children, files in os.walk(root, followlinks=False):
        children[:] = sorted(child for child in children if child not in IGNORED_DIRECTORIES)
        for filename in sorted(files):
            if not filename.endswith(".py"):
                continue
            path = os.path.join(directory, filename)
            relative_path = os.path.relpath(path, root).replace(os.sep, "/")
            if is_test_file(relative_path) or os.path.islink(path):
                continue
            try:
                if os.path.getsize(path) <= MAX_FILE_BYTES:
                    paths.append((relative_path, path))
            except OSError:
                continue
    return sorted(paths)


def inventory(root: str) -> dict[str, Any]:
    modules = []
    errors = []
    symbol_count = 0
    files_scanned = 0
    files = python_files(root)
    for relative_path, path in files[:MAX_FILES]:
        if symbol_count >= MAX_SYMBOLS:
            break
        files_scanned += 1
        try:
            with open(path, "r", encoding="utf-8") as source_file:
                tree = ast.parse(source_file.read(), filename=relative_path)
        except (OSError, UnicodeError, SyntaxError) as error:
            errors.append({"path": relative_path, "error": str(error)[:200]})
            continue
        classes = [class_info(node) for node in tree.body if isinstance(node, ast.ClassDef)]
        functions = [
            function_info(node)[0]
            for node in tree.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        ]
        available = MAX_SYMBOLS - symbol_count
        selected_classes = []
        for item in classes:
            if available <= 0:
                break
            method_count = min(len(item["m"]), max(0, available - 1))
            selected_classes.append({**item, "m": item["m"][:method_count]})
            consumed = 1 + method_count
            available -= consumed
            symbol_count += consumed
        selected_functions = functions[:available]
        symbol_count += len(selected_functions)
        if selected_classes or selected_functions:
            modules.append(
                {
                    "p": relative_path,
                    "i": module_imports(tree),
                    "c": selected_classes,
                    "f": selected_functions,
                }
            )
    return {
        "tool": "python_stdlib_ast",
        "language": "python",
        "files_discovered": len(files),
        "files_scanned": files_scanned,
        "symbol_count": symbol_count,
        "truncated": files_scanned < len(files) or symbol_count >= MAX_SYMBOLS,
        "schema": (
            "module={p:path,i:imports,c:classes,f:functions}; "
            "class={n:name,r:[start_line,end_line],b:bases,s:state_fields,m:methods}; "
            "function_or_method={n:name,r:[start_line,end_line]}"
        ),
        "modules": modules,
        "errors": errors[:20],
    }


if __name__ == "__main__":
    project_root = os.path.realpath(sys.argv[1])
    print(json.dumps(inventory(project_root), separators=(",", ":")))
