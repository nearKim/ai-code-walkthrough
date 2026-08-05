import ast
import hashlib
import json
import os
import re
import sys
from collections import defaultdict
from typing import Any, Optional, Union

try:
    import tomllib
except ImportError:
    tomllib = None

ANALYZER_VERSION = 3
IGNORED_DIRECTORIES = {
    ".ai-code-walkthrough",
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
MAX_FACTS = 24
MAX_TEXT = 400
SAFE_CALLS = {
    "abs", "all", "any", "bool", "dict", "divmod", "enumerate", "filter", "float",
    "frozenset", "int", "isinstance", "issubclass", "iter", "len", "list", "map",
    "max", "min", "next", "range", "reversed", "round", "set", "slice", "sorted",
    "str", "sum", "tuple", "zip",
}
STDLIB_MODULES = getattr(sys, "stdlib_module_names", frozenset())


def expression(node: Optional[ast.AST]) -> Optional[str]:
    if node is None:
        return None
    try:
        return ast.unparse(node)[:160]
    except (RecursionError, ValueError):
        return getattr(node, "id", None) or getattr(node, "attr", None)


def docstring(node: Union[ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef]) -> Optional[str]:
    value = ast.get_docstring(node, clean=True)
    return value[:MAX_TEXT] if value else None


def first_sentence(value: Optional[str]) -> Optional[str]:
    if not value:
        return None
    return re.split(r"(?<=[.!?])\s+", value.strip(), maxsplit=1)[0][:160]


def target_fields(node: ast.AST) -> list[str]:
    if isinstance(node, (ast.Tuple, ast.List)):
        return [field for item in node.elts for field in target_fields(item)]
    if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id in {"self", "cls"}:
        return [node.attr]
    return []


class FunctionFactsVisitor(ast.NodeVisitor):
    def __init__(self) -> None:
        self.state_writes: set[str] = set()
        self.calls: list[dict[str, Any]] = []
        self.branches: list[dict[str, Any]] = []
        self.return_count = 0
        self.raise_count = 0
        self.safe = True

    def visit_Assign(self, node: ast.Assign) -> None:
        for target in node.targets:
            self.state_writes.update(target_fields(target))
        self.generic_visit(node)

    def visit_AnnAssign(self, node: ast.AnnAssign) -> None:
        self.state_writes.update(target_fields(node.target))
        self.generic_visit(node)

    def visit_AugAssign(self, node: ast.AugAssign) -> None:
        self.state_writes.update(target_fields(node.target))
        self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> None:
        target = expression(node.func)
        if target and len(self.calls) < MAX_FACTS:
            self.calls.append({"t": target, "l": node.lineno})
        if not isinstance(node.func, ast.Name) or node.func.id not in SAFE_CALLS:
            self.safe = False
        self.generic_visit(node)

    def visit_If(self, node: ast.If) -> None:
        self._branch("if", node, node.test)
        self.generic_visit(node)

    def visit_While(self, node: ast.While) -> None:
        self._branch("while", node, node.test)
        self.generic_visit(node)

    def visit_For(self, node: ast.For) -> None:
        self._branch("for", node, node.iter)
        self.generic_visit(node)

    def visit_AsyncFor(self, node: ast.AsyncFor) -> None:
        self._branch("async_for", node, node.iter)
        self.safe = False
        self.generic_visit(node)

    def visit_Match(self, node: ast.Match) -> None:
        self._branch("match", node, node.subject)
        self.generic_visit(node)

    def visit_Try(self, node: ast.Try) -> None:
        self._branch("try", node, None)
        self.generic_visit(node)

    def visit_Return(self, node: ast.Return) -> None:
        self.return_count += 1
        self.generic_visit(node)

    def visit_Raise(self, node: ast.Raise) -> None:
        self.raise_count += 1
        self.generic_visit(node)

    def visit_Global(self, node: ast.Global) -> None:
        self.safe = False

    def visit_Nonlocal(self, node: ast.Nonlocal) -> None:
        self.safe = False

    def visit_Yield(self, node: ast.Yield) -> None:
        self.safe = False
        self.generic_visit(node)

    def visit_YieldFrom(self, node: ast.YieldFrom) -> None:
        self.safe = False
        self.generic_visit(node)

    def visit_Await(self, node: ast.Await) -> None:
        self.safe = False
        self.generic_visit(node)

    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        return

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
        return

    def visit_ClassDef(self, node: ast.ClassDef) -> None:
        return

    def _branch(self, kind: str, node: ast.AST, condition: Optional[ast.AST]) -> None:
        if len(self.branches) < MAX_FACTS:
            fact: dict[str, Any] = {"k": kind, "l": node.lineno}
            rendered = expression(condition)
            if rendered:
                fact["x"] = rendered
            self.branches.append(fact)


def fully_annotated(node: Union[ast.FunctionDef, ast.AsyncFunctionDef]) -> bool:
    arguments = [*node.args.posonlyargs, *node.args.args, *node.args.kwonlyargs]
    arguments = [argument for argument in arguments if argument.arg not in {"self", "cls"}]
    return node.returns is not None and all(argument.annotation is not None for argument in arguments)


def function_info(
    node: Union[ast.FunctionDef, ast.AsyncFunctionDef],
    safe_module: bool,
) -> tuple[dict[str, Any], set[str]]:
    visitor = FunctionFactsVisitor()
    for statement in node.body:
        visitor.visit(statement)
    info: dict[str, Any] = {
        "n": node.name,
        "r": [node.lineno, getattr(node, "end_lineno", node.lineno)],
        "w": sorted(visitor.state_writes)[:MAX_FIELDS],
        "c": visitor.calls,
        "q": visitor.branches,
        "u": visitor.return_count,
        "e": visitor.raise_count,
        "t": fully_annotated(node),
        "z": bool(
            safe_module
            and fully_annotated(node)
            and visitor.safe
            and not visitor.state_writes
            and not isinstance(node, ast.AsyncFunctionDef)
        ),
    }
    value = docstring(node)
    if value:
        info["d"] = value
    return info, visitor.state_writes


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
            method, state_writes = function_info(item, False)
            methods.append(method)
            fields.update(state_writes)
    info: dict[str, Any] = {
        "n": node.name,
        "r": [node.lineno, getattr(node, "end_lineno", node.lineno)],
        "b": [value for value in (expression(item) for item in node.bases) if value],
        "s": sorted(fields)[:MAX_FIELDS],
        "m": methods,
    }
    value = docstring(node)
    if value:
        info["d"] = value
    return info


def import_facts(tree: ast.Module) -> list[dict[str, Any]]:
    imports = []
    for node in tree.body:
        if isinstance(node, ast.Import):
            imports.extend({"n": alias.name, "l": node.lineno} for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            module = "." * node.level + (node.module or "")
            imports.append({"n": module, "l": node.lineno})
    return imports


def is_test_file(relative_path: str) -> bool:
    parts = relative_path.split("/")
    return parts[-1].startswith("test_") or any(part in {"test", "tests"} for part in parts)


def python_files(root: str) -> tuple[list[tuple[str, str]], list[dict[str, str]]]:
    paths = []
    skipped = []
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
                size = os.path.getsize(path)
                if size <= MAX_FILE_BYTES:
                    paths.append((relative_path, path))
                else:
                    skipped.append({"path": relative_path, "reason": f"file exceeds {MAX_FILE_BYTES} bytes"})
            except OSError as error:
                skipped.append({"path": relative_path, "reason": str(error)[:160]})
    return sorted(paths), skipped


def source_fingerprint(root: str, files: list[tuple[str, str]], skipped: list[dict[str, str]]) -> str:
    digest = hashlib.sha256()
    for relative_path, path in files:
        digest.update(relative_path.encode("utf-8"))
        digest.update(b"\0")
        try:
            with open(path, "rb") as source_file:
                for chunk in iter(lambda: source_file.read(64 * 1024), b""):
                    digest.update(chunk)
        except OSError as error:
            digest.update(str(error).encode("utf-8", errors="replace"))
        digest.update(b"\0")
    for item in skipped:
        digest.update(item["path"].encode("utf-8"))
        digest.update(b"\0")
        digest.update(item["reason"].encode("utf-8", errors="replace"))
        digest.update(b"\0")
    metadata_path = os.path.join(root, "pyproject.toml")
    if os.path.isfile(metadata_path):
        digest.update(b"pyproject.toml\0")
        try:
            with open(metadata_path, "rb") as metadata_file:
                digest.update(metadata_file.read())
        except OSError as error:
            digest.update(str(error).encode("utf-8", errors="replace"))
    return digest.hexdigest()


def module_name(path: str) -> str:
    name = path[:-3].replace("/", ".")
    if name.startswith(("src.", "lib.")):
        name = name.split(".", 1)[1]
    return name[:-9] if name.endswith(".__init__") else name


def module_group(path: str) -> str:
    parts = path.split("/")
    if parts[0] in {"src", "lib"} and len(parts) > 1:
        return parts[1].removesuffix(".py")
    return parts[0] if len(parts) > 1 else "root"


def component_id(group: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "-", group).strip("-") or "root"
    return f"python-{slug}"


def stable_id(*parts: str) -> str:
    return hashlib.sha256("\0".join(parts).encode("utf-8")).hexdigest()[:12]


def project_metadata(root: str) -> dict[str, Any]:
    path = os.path.join(root, "pyproject.toml")
    if tomllib is None or not os.path.isfile(path):
        return {}
    try:
        with open(path, "rb") as source:
            project = tomllib.load(source).get("project", {})
        with open(path, "r", encoding="utf-8") as source:
            lines = source.readlines()
    except (OSError, UnicodeError, tomllib.TOMLDecodeError):
        return {}
    scripts = project.get("scripts", {})
    script_lines = {}
    for name in scripts if isinstance(scripts, dict) else []:
        pattern = re.compile(rf"^\s*{re.escape(str(name))}\s*=")
        script_lines[str(name)] = next(
            (index for index, line in enumerate(lines, 1) if pattern.search(line)),
            1,
        )
    return {
        "name": project.get("name"),
        "description": project.get("description"),
        "scripts": scripts if isinstance(scripts, dict) else {},
        "script_lines": script_lines,
    }


def reachable_components(
    entry_module: str,
    modules: dict[str, dict[str, Any]],
) -> list[str]:
    pending = [entry_module]
    visited = set()
    groups = set()
    while pending:
        name = pending.pop()
        if name in visited or name not in modules:
            continue
        visited.add(name)
        module = modules[name]
        groups.add(module_group(module["p"]))
        for imported in module["x"]:
            target = resolve_import(name, imported["n"], modules)
            if target and target not in visited:
                pending.append(target)
    return [component_id(group) for group in sorted(groups)]


def build_containers(modules: dict[str, dict[str, Any]], metadata: dict[str, Any]) -> list[dict[str, Any]]:
    containers = []
    for script_name, raw_target in sorted(metadata.get("scripts", {}).items()):
        if not isinstance(raw_target, str):
            continue
        entry_module = raw_target.split(":", 1)[0]
        module = modules.get(entry_module)
        component_ids = reachable_components(entry_module, modules)
        if module is None or not component_ids:
            continue
        line = metadata.get("script_lines", {}).get(script_name, 1)
        module_item = {"r": module["r"]}
        entry_evidence = [{
            "kind": "entrypoint",
            "label": script_name,
            "file_path": "pyproject.toml",
            "start_line": line,
            "end_line": line,
            "text": f"Declares {script_name} as {raw_target}.",
        }, evidence(
            "module",
            entry_module,
            module["p"],
            module_item,
            f"Implements the {script_name} entry module.",
        )]
        is_mcp = "mcp" in script_name.lower() or "mcp" in raw_target.lower()
        containers.append({
            "id": f"entry-{stable_id(script_name, raw_target)}",
            "name": script_name,
            "kind": "mcp_server" if is_mcp else "command_line_application",
            "responsibility": f"Runs the {raw_target} entry point.",
            "component_ids": component_ids,
            "evidence": entry_evidence,
            "uncertain": False,
        })
    return containers


def behavior(item: dict[str, Any], fallback: str) -> str:
    declared = item.get("d")
    if declared:
        return declared
    facts = []
    branches = item.get("q", [])
    writes = item.get("w", [])
    calls = item.get("c", [])
    if branches:
        facts.append("branch predicates " + ", ".join(
            f"{fact['k']} {fact.get('x', '')} at L{fact['l']}".replace("  ", " ")
            for fact in branches[:5]
        ))
    if writes:
        facts.append("state writes to " + ", ".join(writes[:5]))
    if calls:
        facts.append("call expressions " + ", ".join(fact["t"] for fact in calls[:5]))
    if item.get("u"):
        facts.append(f"{item['u']} return statement(s)")
    if item.get("e"):
        facts.append(f"{item['e']} raise statement(s)")
    return ("Contains " + "; ".join(facts) + ".") if facts else fallback


def evidence(kind: str, label: str, path: str, item: dict[str, Any], text: str) -> dict[str, Any]:
    return {
        "kind": kind,
        "label": label,
        "file_path": path,
        "start_line": item["r"][0],
        "end_line": item["r"][1],
        "text": text,
    }


def resolve_import(source_module: str, imported: str, modules: dict[str, dict[str, Any]]) -> Optional[str]:
    if imported.startswith("."):
        level = len(imported) - len(imported.lstrip("."))
        suffix = imported[level:]
        base = source_module.split(".")[:-1]
        if level > 1:
            base = base[: -(level - 1)] if level - 1 <= len(base) else []
        imported = ".".join([*base, *([suffix] if suffix else [])])
    candidate = imported
    while candidate:
        if candidate in modules:
            return candidate
        candidate = candidate.rsplit(".", 1)[0] if "." in candidate else ""
    return None


def build_architecture(
    root: str,
    modules: list[dict[str, Any]],
    truncated: bool,
    errors: list[dict[str, str]],
    skipped: list[dict[str, str]],
) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    by_name = {module_name(module["p"]): module for module in modules}
    for module in modules:
        grouped[module_group(module["p"])].append(module)

    components = []
    for group in sorted(grouped):
        items = grouped[group]
        responsibilities = []
        key_symbols = []
        module_evidence = []
        class_count = sum(len(module["c"]) for module in items)
        function_count = sum(len(module["f"]) for module in items)
        declared = next((first_sentence(module.get("d")) for module in items if module["p"].endswith("__init__.py") and module.get("d")), None)
        structural = f"Contains {class_count} class(es) and {function_count} top-level function(s) across {len(items)} Python module(s)."

        for module in items:
            module_text = module.get("d") or f"Defines {len(module['c'])} class(es) and {len(module['f'])} top-level function(s)."
            module_evidence.append(evidence("module", module_name(module["p"]), module["p"], module, module_text))
            for item in module["c"]:
                class_text = item.get("d") or (
                    "Owns methods " + ", ".join(f"{method['n']}()" for method in item["m"]) + "."
                    if item["m"] else f"Defines class {item['n']}."
                )
                owner_evidence = [evidence("class", item["n"], module["p"], item, class_text)]
                owner_evidence.extend(
                    evidence(
                        "method",
                        f"{item['n']}.{method['n']}",
                        module["p"],
                        method,
                        behavior(method, f"Defines method {item['n']}.{method['n']}()."),
                    )
                    for method in item["m"]
                )
                responsibilities.append({
                    "id": f"class-{stable_id(module['p'], item['n'])}",
                    "title": first_sentence(item.get("d")) or class_text,
                    "description": class_text,
                    "evidence": owner_evidence,
                    "collaborator_component_ids": [],
                    "relationship_ids": [],
                    "uncertain": False,
                })
                key_symbols.append(item["n"])
            for item in module["f"]:
                function_text = behavior(item, f"Defines function {item['n']}().")
                responsibilities.append({
                    "id": f"function-{stable_id(module['p'], item['n'])}",
                    "title": first_sentence(item.get("d")) or function_text,
                    "description": function_text,
                    "evidence": [evidence("function", item["n"], module["p"], item, function_text)],
                    "collaborator_component_ids": [],
                    "relationship_ids": [],
                    "uncertain": False,
                })
                key_symbols.append(item["n"])

        components.append({
            "id": component_id(group),
            "name": "Root modules" if group == "root" else group.replace("_", " "),
            "kind": "python_package",
            "responsibility": declared or structural,
            "responsibilities": responsibilities,
            "key_paths": [module["p"] for module in items],
            "key_symbols": sorted(set(key_symbols)),
            "evidence": module_evidence,
            "uncertain": False,
        })

    relationships = []
    relationship_evidence: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for source_name, source in by_name.items():
        source_group = module_group(source["p"])
        for imported in source["x"]:
            target_name = resolve_import(source_name, imported["n"], by_name)
            if not target_name:
                continue
            target = by_name[target_name]
            target_group = module_group(target["p"])
            if source_group == target_group:
                continue
            relationship_evidence[(source_group, target_group)].append({
                "kind": "import",
                "label": imported["n"],
                "file_path": source["p"],
                "start_line": imported["l"],
                "end_line": imported["l"],
                "text": f"{source_name} imports {target_name}.",
            })
    for (source_group, target_group), items in sorted(relationship_evidence.items()):
        relationships.append({
            "id": f"import-{stable_id(source_group, target_group)}",
            "from_component_id": component_id(source_group),
            "to_component_id": component_id(target_group),
            "kind": "imports",
            "description": f"{source_group} imports {target_group}.",
            "evidence": items[:10],
            "uncertain": False,
        })

    class_count = sum(len(module["c"]) for module in modules)
    function_count = sum(len(module["f"]) for module in modules)
    metadata = project_metadata(root)
    containers = build_containers(by_name, metadata)
    notes = ["Architecture, ownership, and import edges are derived from the persisted Python AST inventory."]
    if truncated:
        notes.append("The mechanical inventory reached its configured file or symbol limit.")
    if errors:
        notes.append(f"{len(errors)} Python file(s) could not be parsed; they are excluded from verified claims.")
    if skipped:
        notes.append(f"{len(skipped)} oversized or unreadable Python file(s) were excluded from verified claims.")
    notes.append("Dynamic dispatch and unresolved imports are omitted rather than inferred.")
    return {
        "system_name": metadata.get("name"),
        "system_purpose": metadata.get("description") or f"Python codebase with {len(modules)} production module(s), {class_count} class(es), and {function_count} top-level function(s).",
        "containers": containers,
        "components": components,
        "relationships": relationships,
        "cross_cutting_concerns": [],
        "coverage_notes": notes,
    }


def safe_module(tree: ast.Module) -> bool:
    for node in tree.body:
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            continue
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            names = [alias.name.split(".", 1)[0] for alias in node.names]
            if isinstance(node, ast.ImportFrom) and node.module:
                names.append(node.module.split(".", 1)[0])
            if any(name not in STDLIB_MODULES for name in names):
                return False
            continue
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if node.decorator_list:
                return False
            defaults = [*node.args.defaults, *(value for value in node.args.kw_defaults if value is not None)]
            try:
                for value in defaults:
                    ast.literal_eval(value)
            except (ValueError, TypeError):
                return False
            annotations = [
                node.returns,
                *(argument.annotation for argument in [*node.args.posonlyargs, *node.args.args, *node.args.kwonlyargs]),
            ]
            if any(isinstance(item, ast.Call) for annotation in annotations if annotation for item in ast.walk(annotation)):
                return False
            continue
        if isinstance(node, (ast.Assign, ast.AnnAssign)):
            value = node.value
            try:
                ast.literal_eval(value)
            except (ValueError, TypeError):
                return False
            continue
        return False
    return True


def inventory(root: str) -> dict[str, Any]:
    modules = []
    errors = []
    symbol_count = 0
    files_scanned = 0
    files, skipped = python_files(root)
    fingerprint = source_fingerprint(root, files, skipped)
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
        module_is_safe = safe_module(tree)
        classes = [class_info(node) for node in tree.body if isinstance(node, ast.ClassDef)]
        functions = [
            function_info(node, module_is_safe)[0]
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
        imports = import_facts(tree)
        module: dict[str, Any] = {
            "p": relative_path,
            "r": [1, max((getattr(node, "end_lineno", 1) for node in tree.body), default=1)],
            "i": sorted({item["n"] for item in imports}),
            "x": imports,
            "c": selected_classes,
            "f": selected_functions,
        }
        value = docstring(tree)
        if value:
            module["d"] = value
        modules.append(module)
    truncated = bool(skipped) or files_scanned < len(files) or symbol_count >= MAX_SYMBOLS
    payload = {
        "tool": "python_stdlib_ast",
        "analyzer_version": ANALYZER_VERSION,
        "source_fingerprint": fingerprint,
        "language": "python",
        "files_discovered": len(files) + len(skipped),
        "files_scanned": files_scanned,
        "symbol_count": symbol_count,
        "truncated": truncated,
        "schema": (
            "module={p:path,r:range,i:imports,x:import_facts,c:classes,f:functions,d:docstring}; "
            "class={n:name,r:range,b:bases,s:state_fields,m:methods,d:docstring}; "
            "callable={n:name,r:range,d:docstring,w:state_writes,c:calls,q:branches,u:returns,e:raises,t:typed,z:safe_path_target}"
        ),
        "modules": modules,
        "errors": errors[:20],
        "skipped_files": skipped[:20],
    }
    payload["architecture"] = build_architecture(root, modules, truncated, errors, skipped)
    return payload


if __name__ == "__main__":
    project_root = os.path.realpath(sys.argv[1])
    files, skipped = python_files(project_root)
    if len(sys.argv) > 2 and sys.argv[2] == "--fingerprint":
        result = {
            "analyzer_version": ANALYZER_VERSION,
            "source_fingerprint": source_fingerprint(project_root, files, skipped),
        }
    else:
        result = inventory(project_root)
    print(json.dumps(result, separators=(",", ":")))
