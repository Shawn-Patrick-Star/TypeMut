from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple


BASE_DIAGNOSTIC_TERMS = (
    "root cause", "regression", "regressed", "introduced", "bisect",
    "fix", "fixed", "patch", "commit", "pull request", "reproducer",
    "reproduce", "workaround", "crash", "assert", "assertion", "fatal",
    "segfault", "SIGSEGV", "exception", "wrong code", "miscompile",
    "incorrect result", "stack trace", "failure", "fails", "failed",
)


@dataclass(frozen=True)
class EvidenceConfig:
    description_chars: int
    comments_chars_with_patch: int
    comments_chars_without_patch: int
    java_chars_with_patch: int
    java_chars_without_patch: int
    patch_chars: int
    max_comments: int
    diagnostic_terms: Tuple[str, ...] = BASE_DIAGNOSTIC_TERMS
    patch_path_priority_terms: Tuple[str, ...] = ()
    patch_hunk_priority_terms: Tuple[str, ...] = ()


@dataclass
class CasePackage:
    case_id: str
    text: str
    chars: int
    has_patch: bool
    original_java_chars: int
    original_patch_chars: int


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def clip_text(text: str, budget: int, marker: str = "[... locally truncated ...]") -> str:
    if budget <= 0:
        return ""
    if len(text) <= budget:
        return text
    head = max(int(budget * 0.68), 1)
    tail = max(budget - head - len(marker) - 4, 1)
    return text[:head] + "\n" + marker + "\n" + text[-tail:]


def canonical_metadata_file(case_dir: Path) -> Optional[Path]:
    exact = case_dir / f"{case_dir.name}.json"
    if exact.exists():
        return exact

    base_id = case_dir.name.split("_")[0]
    candidates = []
    for p in case_dir.glob("*.json"):
        low = p.name.lower()
        if low.startswith("llm_") or "_type" in low or "jit_opt" in low:
            continue
        if p.stem.startswith(base_id):
            candidates.append(p)
    return sorted(candidates)[0] if candidates else None


def find_java_files(case_dir: Path) -> List[Path]:
    return sorted(p for p in case_dir.rglob("*.java") if ".meta" not in p.parts)


def find_patch_files(case_dir: Path) -> List[Path]:
    return sorted(
        p for p in case_dir.iterdir()
        if p.is_file() and p.suffix.lower() in {".patch", ".diff"}
    )


def valid_case_dirs(root: Path) -> List[str]:
    result = []
    if not root.exists():
        return result
    for d in root.iterdir():
        if d.is_dir() and not d.name.startswith("."):
            if canonical_metadata_file(d) is not None and find_java_files(d):
                result.append(d.name)
    return sorted(result)


def _first(meta: Dict[str, Any], keys: Sequence[str], default: Any = "") -> Any:
    for key in keys:
        value = meta.get(key)
        if value not in (None, "", [], {}):
            return value
    return default


def _short(value: Any, budget: int = 1200) -> str:
    if value in (None, "", [], {}):
        return ""
    if isinstance(value, str):
        return clip_text(value, budget)
    try:
        return clip_text(json.dumps(value, ensure_ascii=False, indent=2), budget)
    except Exception:
        return clip_text(str(value), budget)


def _load_metadata(path: Path) -> Dict[str, Any]:
    try:
        obj = json.loads(read_text(path))
        return obj if isinstance(obj, dict) else {"raw": obj}
    except Exception:
        return {"raw_text": read_text(path)}


def _normalize_comment(comment: Any, index: int) -> Dict[str, str]:
    if isinstance(comment, str):
        return {"index": str(index), "author": "", "created": "", "text": comment}
    if not isinstance(comment, dict):
        return {"index": str(index), "author": "", "created": "", "text": str(comment)}

    text = _first(comment, ("body", "content", "text", "comment", "description"), "")
    author = _first(comment, ("author", "user", "creator", "name"), "")
    if isinstance(author, dict):
        author = _first(author, ("displayName", "name", "login", "username"), "")
    created = _first(comment, ("created", "created_at", "date", "updated"), "")
    return {
        "index": str(index),
        "author": _short(author, 100),
        "created": _short(created, 100),
        "text": str(text or ""),
    }


def _diagnostic_regex(terms: Sequence[str]) -> re.Pattern:
    escaped = sorted((re.escape(t) for t in terms if t), key=len, reverse=True)
    return re.compile("|".join(escaped), re.IGNORECASE) if escaped else re.compile(r"$^")


def _comment_score(comment: Dict[str, str], index: int, total: int, diagnostic_re: re.Pattern) -> int:
    text = comment["text"]
    score = 0
    if index == 0:
        score += 8
    if index == total - 1:
        score += 8
    score += min(len(diagnostic_re.findall(text)) * 3, 30)
    if "```" in text:
        score += 4
    if re.search(r"\bat\s+[\w.$]+\([^)]*\)", text):
        score += 4
    if re.search(r"https?://(?:github\.com|bugs\.openjdk\.org)", text):
        score += 3
    if re.search(r"\b[0-9a-f]{7,40}\b", text, re.IGNORECASE):
        score += 2
    if len(text.strip()) < 40:
        score -= 4
    return score


def _select_comments(comments: Any, max_comments: int, budget: int, terms: Sequence[str]) -> str:
    if not isinstance(comments, list) or not comments or budget <= 0:
        return "None selected."

    normalized = [_normalize_comment(c, i) for i, c in enumerate(comments)]
    diag_re = _diagnostic_regex(terms)
    total = len(normalized)
    ranked = sorted(
        range(total),
        key=lambda i: (-_comment_score(normalized[i], i, total, diag_re), i),
    )
    chosen = set(ranked[:max_comments])
    chosen.update({0, total - 1})
    ordered = sorted(chosen)

    parts: List[str] = []
    remaining = budget
    for pos, i in enumerate(ordered):
        if remaining <= 0:
            break
        c = normalized[i]
        prefix = f"[Comment {i+1}/{total}]"
        if c["author"]:
            prefix += f" author={c['author']}"
        if c["created"]:
            prefix += f" date={c['created']}"
        prefix += "\n"
        left = max(len(ordered) - pos, 1)
        share = max(600, min(3000, remaining // left))
        block = prefix + clip_text(c["text"], max(share - len(prefix), 200)) + "\n"
        block = clip_text(block, remaining)
        parts.append(block)
        remaining -= len(block)
    return clip_text("\n".join(parts), budget)


def compact_metadata(path: Path, cfg: EvidenceConfig, has_patch: bool) -> str:
    meta = _load_metadata(path)
    title = _first(meta, ("title", "summary", "name"), "")
    description = _first(meta, ("body", "description", "details", "text"), "")
    labels = _first(meta, ("label_names", "labels", "components", "component"), "")
    status = _first(meta, ("status", "state"), "")
    repository = _first(meta, ("repository", "project", "issue_tracker"), "")
    comments = _first(meta, ("comments", "comment"), [])
    fixing_meta = _first(meta, ("fixing_commits", "fixes", "fix_commits", "resolution"), "")
    if "raw_text" in meta and not description:
        description = meta["raw_text"]

    comments_budget = (
        cfg.comments_chars_with_patch if has_patch else cfg.comments_chars_without_patch
    )
    parts = [
        f"Metadata file: {path.name}",
        f"Repository/Tracker: {_short(repository, 500)}",
        f"Status: {_short(status, 200)}",
        f"Labels/Components: {_short(labels, 1000)}",
        f"Title: {_short(title, 1600)}",
        "",
        "Description:",
        clip_text(str(description or "None"), cfg.description_chars),
        "",
        "Selected diagnostic comments:",
        _select_comments(comments, cfg.max_comments, comments_budget, cfg.diagnostic_terms),
    ]
    if fixing_meta:
        parts += ["", "Fix metadata:", _short(fixing_meta, 1800)]
    return "\n".join(parts)


JAVA_LANDMARK_RE = re.compile(
    r"(^\s*(?:package|import)\s+|\b(?:class|interface|enum|record)\s+\w+|"
    r"\bstatic\s+void\s+main\s*\(|@Test\b|\b(?:test|run|main)\w*\s*\(|"
    r"\bassert\b|\bthrow\s+new\b|CompileCommand|TestFramework|WhiteBox)",
    re.IGNORECASE,
)

JAVA_TYPE_LANDMARK_RE = re.compile(
    r"(\binstanceof\b|"
    r"\b(?:MethodHandle|MethodType|invokeExact|invokeWithArguments)\b|"
    r"\(\s*(?:byte|short|char|int|long|float|double|boolean)\s*\)|"
    r"\b(?:Byte|Short|Character|Integer|Long|Float|Double|Boolean)\s*\.\s*valueOf\s*\(|"
    r"\.(?:byteValue|shortValue|charValue|intValue|longValue|floatValue|doubleValue|booleanValue)\s*\()",
    re.IGNORECASE,
)


def _java_priority(path: Path) -> Tuple[int, str]:
    name = path.name.lower()
    score = 10 if any(x in name for x in ("test", "repro", "main")) else 0
    if name in {"test.java", "main.java"}:
        score += 10
    return (-score, str(path))


def _render_java_lines(lines: Sequence[str], keep: Sequence[int]) -> str:
    out, prev = [], None
    for i in sorted(set(keep)):
        if prev is not None and i > prev + 1:
            out.append("    ... [Java lines omitted locally] ...")
        out.append(lines[i])
        prev = i
    return "\n".join(out)


def _compact_java_text(text: str, budget: int) -> str:
    if len(text) <= budget:
        return text

    lines = text.splitlines()
    keep = set(range(min(16, len(lines))))
    keep.update(range(max(0, len(lines) - 8), len(lines)))

    # Add the highest-value local windows first. Type-sensitive trigger sites
    # outrank generic structural landmarks so they survive tight budgets.
    candidates = []
    for i, line in enumerate(lines):
        if JAVA_TYPE_LANDMARK_RE.search(line):
            candidates.append((4, i, 2))
        elif re.search(r"\bmain\s*\(|@Test\b|\btest\w*\s*\(", line, re.I):
            candidates.append((5, i, 4))
        elif JAVA_LANDMARK_RE.search(line):
            candidates.append((2, i, 1))

    base = _render_java_lines(lines, keep)
    if len(base) > budget:
        return clip_text(
            base,
            budget,
            "[... Java representation further truncated ...]",
        )

    for _, i, radius in sorted(candidates, key=lambda x: (-x[0], x[1])):
        proposed = set(keep)
        proposed.update(range(max(0, i - radius), min(len(lines), i + radius + 1)))
        rendered = _render_java_lines(lines, proposed)
        if len(rendered) <= budget:
            keep = proposed

    return _render_java_lines(lines, keep)


def compact_java(
    case_dir: Path, files: Sequence[Path], budget: int
) -> Tuple[str, int]:
    ordered = sorted(files, key=_java_priority)
    raw = [(p, read_text(p)) for p in ordered]
    total_raw = sum(len(t) for _, t in raw)
    parts = [f"Java files={len(raw)}; original chars={total_raw}; local budget={budget}"]
    if total_raw <= budget:
        for p, text in raw:
            parts.append(f"\n--- Java File: {p.relative_to(case_dir)} (full) ---\n{text}")
        return "\n".join(parts), total_raw

    remaining = max(budget - 800, 800)
    for idx, (p, text) in enumerate(raw):
        if remaining <= 300:
            break
        left = len(raw) - idx
        share = min(max(1600, remaining // max(left, 1)), 8000, remaining)
        compacted = _compact_java_text(text, share)
        block = (
            f"\n--- Java File: {p.relative_to(case_dir)} "
            f"(original={len(text)}, supplied={len(compacted)}) ---\n{compacted}"
        )
        block = clip_text(block, remaining)
        parts.append(block)
        remaining -= len(block)
    parts.append("\n[NOTE: Java source was locally reduced to fit the evidence budget.]")
    return clip_text("\n".join(parts), budget + 800), total_raw


def _split_diff(text: str) -> List[Tuple[str, List[str]]]:
    sections, current, current_path = [], [], "unknown"

    def flush():
        nonlocal current
        if current:
            sections.append((current_path, current))
        current = []

    for line in text.splitlines():
        if line.startswith("diff --git "):
            flush()
            current = [line]
            m = re.match(r"diff --git a/(.+?) b/(.+)", line)
            current_path = m.group(2) if m else "unknown"
        else:
            current.append(line)
            if line.startswith("+++ b/"):
                current_path = line[6:].strip()
    flush()
    return sections


def _path_score(path: str, priority_terms: Sequence[str]) -> int:
    p = path.lower()
    score = sum(12 for term in priority_terms if term.lower() in p)
    if p.endswith((".cpp", ".hpp", ".c", ".h", ".cc", ".java")):
        score += 5
    if any(x in p for x in ("/test/", "/tests/", "docs/", "doc/")):
        score -= 5
    return score


def _hunk_score(hunk: Sequence[str], priority_terms: Sequence[str]) -> int:
    """Rank patch hunks so root-cause compiler changes survive truncation."""
    if not hunk:
        return 0

    header = hunk[0]
    changed = "\n".join(
        line
        for line in hunk[1:]
        if (line.startswith("+") and not line.startswith("+++"))
        or (line.startswith("-") and not line.startswith("---"))
    )
    searchable = (header + "\n" + changed).lower()

    score = 0
    for term in priority_terms:
        if term and term.lower() in searchable:
            score += 10
            if term.lower() in header.lower():
                score += 5

    # Prefer substantive compiler changes over tiny metadata-only hunks.
    changed_lines = sum(
        1
        for line in hunk[1:]
        if (line.startswith("+") and not line.startswith("+++"))
        or (line.startswith("-") and not line.startswith("---"))
    )
    score += min(changed_lines, 20)
    return score


def _compact_diff_section(
    path: str,
    lines: Sequence[str],
    context: int = 3,
    priority_terms: Sequence[str] = (),
) -> str:
    headers, hunks, current = [], [], None
    for line in lines:
        if line.startswith("@@"):
            current = [line]
            hunks.append(current)
        elif current is not None:
            current.append(line)
        elif line.startswith(("diff --git ", "--- ", "+++ ", "rename from ", "rename to ")):
            headers.append(line)

    ranked_hunks = sorted(
        enumerate(hunks),
        key=lambda item: (-_hunk_score(item[1], priority_terms), item[0]),
    )

    out = [f"File: {path}"] + headers
    for _, hunk in ranked_hunks:
        body = hunk[1:]
        changed = [
            i for i, line in enumerate(body)
            if (line.startswith("+") and not line.startswith("+++"))
            or (line.startswith("-") and not line.startswith("---"))
        ]
        if not changed:
            continue
        out.append(hunk[0])
        keep = set()
        for i in changed:
            keep.update(range(max(0, i - context), min(len(body), i + context + 1)))
        prev = None
        for i in sorted(keep):
            if prev is not None and i > prev + 1:
                out.append(" ... [unchanged patch context omitted] ...")
            out.append(body[i])
            prev = i
    return "\n".join(out)


def _patch_priority(path: Path) -> Tuple[int, str]:
    name = path.name.lower()
    penalty = 20 if "backport" in name else 0
    if re.search(r"(?:jdk|java)[-_]?(?:8u|11u|17u|21u)", name):
        penalty += 10
    return penalty, name


def compact_patches(
    files: Sequence[Path],
    budget: int,
    priority_terms: Sequence[str],
    hunk_priority_terms: Sequence[str] = (),
) -> Tuple[str, int]:
    if not files or budget <= 0:
        return "No fixing patch supplied.", 0

    raw = {path: read_text(path) for path in files}
    total_raw = sum(len(text) for text in raw.values())
    parts = [f"Patch files={len(files)}; local patch budget={budget}"]
    remaining = max(budget - len(parts[0]) - 20, 0)
    ordered = sorted(files, key=_patch_priority)

    for idx, path in enumerate(ordered):
        if remaining <= 200:
            break
        text = raw[path]
        share = min(max(2400, remaining // max(len(ordered) - idx, 1)), remaining)
        sections = _split_diff(text)
        if sections:
            sections.sort(key=lambda x: (-_path_score(x[0], priority_terms), x[0]))
            patch_parts = [f"Patch file: {path.name}; original chars={len(text)}"]
            local_remaining = max(share - len(patch_parts[0]) - 10, 0)
            for diff_path, lines in sections:
                if local_remaining <= 100:
                    break
                section = _compact_diff_section(
                    diff_path,
                    lines,
                    priority_terms=hunk_priority_terms,
                )
                if not section.strip():
                    continue
                block = "\n\n" + clip_text(section, local_remaining)
                patch_parts.append(block)
                local_remaining -= len(block)
            compacted = clip_text("\n".join(patch_parts), share)
        else:
            compacted = f"Patch file: {path.name}\n" + clip_text(text, max(share - 50, 0))

        block = "\n\n" + compacted
        block = clip_text(block, remaining)
        parts.append(block)
        remaining -= len(block)

    return clip_text("\n".join(parts), budget + 400), total_raw


def build_case_package(
    case_dir: Path,
    cfg: EvidenceConfig,
    *,
    include_patches: bool = True,
) -> Optional[CasePackage]:
    meta = canonical_metadata_file(case_dir)
    java_files = find_java_files(case_dir)
    if meta is None or not java_files:
        return None

    patch_files = find_patch_files(case_dir) if include_patches else []
    has_patch = bool(patch_files)
    java_budget = cfg.java_chars_with_patch if has_patch else cfg.java_chars_without_patch

    metadata_text = compact_metadata(meta, cfg, has_patch)
    java_text, original_java_chars = compact_java(case_dir, java_files, java_budget)
    patch_text, original_patch_chars = compact_patches(
        patch_files,
        cfg.patch_chars,
        cfg.patch_path_priority_terms,
        cfg.patch_hunk_priority_terms,
    )

    package = "\n".join(
        [
            f"================ CASE {case_dir.name} ================",
            f"CASE_ID: {case_dir.name}",
            "",
            "### COMPACT ISSUE METADATA",
            metadata_text,
            "",
            "### COMPACT JAVA EVIDENCE",
            java_text,
            "",
            "### COMPACT FIXING-PATCH EVIDENCE",
            patch_text,
            f"================ END CASE {case_dir.name} ================",
        ]
    )
    return CasePackage(
        case_id=case_dir.name,
        text=package,
        chars=len(package),
        has_patch=has_patch,
        original_java_chars=original_java_chars,
        original_patch_chars=original_patch_chars,
    )


def make_batches(
    packages: Sequence[CasePackage], batch_size: int, max_batch_chars: int
) -> List[List[CasePackage]]:
    batches: List[List[CasePackage]] = []
    current: List[CasePackage] = []
    chars = 0
    for pkg in packages:
        added = pkg.chars + 100
        if current and (len(current) >= batch_size or chars + added > max_batch_chars):
            batches.append(current)
            current, chars = [], 0
        current.append(pkg)
        chars += added
        if pkg.chars > max_batch_chars:
            batches.append(current)
            current, chars = [], 0
    if current:
        batches.append(current)
    return batches
