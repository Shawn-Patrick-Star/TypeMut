from __future__ import annotations

import hashlib
import json
import os
import random
import re
import threading
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import requests
from requests import Response
from requests.exceptions import RequestException

REQUEST_TIMEOUT = 60
MAX_RETRIES = 5
BASE_RETRY_DELAY = 2.0

PRINT_LOCK = threading.Lock()
STATE_LOCK = threading.Lock()
INDEX_LOCK = threading.Lock()
PATCH_CACHE_LOCK = threading.Lock()
STOP_EVENT = threading.Event()


class CrawlCancelled(BaseException):
    """Internal cooperative-cancellation signal for crawler worker threads."""


def request_stop() -> None:
    STOP_EVENT.set()


def reset_stop() -> None:
    STOP_EVENT.clear()


def raise_if_stopping() -> None:
    if STOP_EVENT.is_set():
        raise CrawlCancelled()


def interruptible_sleep(seconds: float) -> None:
    """Sleep unless cancellation is requested; wake immediately on Ctrl+C."""
    if seconds <= 0:
        raise_if_stopping()
        return
    if STOP_EVENT.wait(seconds):
        raise CrawlCancelled()


def thread_safe_print(msg: str) -> None:
    with PRINT_LOCK:
        print(msg, flush=True)


def utc_now_iso() -> str:
    return datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def parse_yyyy_mm_dd(value: str) -> date:
    return datetime.strptime(value, "%Y-%m-%d").date()


def date_windows(start_date: date, end_date: date, days: int = 90) -> List[Tuple[date, date]]:
    if end_date < start_date:
        raise ValueError("end_date must be >= start_date")
    out: List[Tuple[date, date]] = []
    cur = start_date
    while cur <= end_date:
        win_end = min(cur + timedelta(days=days - 1), end_date)
        out.append((cur, win_end))
        cur = win_end + timedelta(days=1)
    return out


def get_with_retry(
    session: requests.Session,
    url: str,
    *,
    params: Optional[dict] = None,
    headers: Optional[dict] = None,
    description: str = "",
    min_delay: float = 0.0,
) -> Response:
    last_exc: Optional[Exception] = None
    for attempt in range(1, MAX_RETRIES + 1):
        raise_if_stopping()
        if min_delay:
            interruptible_sleep(min_delay)
        try:
            raise_if_stopping()
            resp = session.get(url, params=params, headers=headers, timeout=REQUEST_TIMEOUT)
            if resp.status_code in (403, 429):
                remaining = resp.headers.get("X-RateLimit-Remaining")
                if remaining == "0":
                    reset = int(resp.headers.get("X-RateLimit-Reset", str(int(time.time()) + 60)))
                    wait = max(reset - int(time.time()), 0) + 2
                else:
                    wait = min((2 ** attempt) * 5, 120)
                thread_safe_print(f"[retry] {description or url}: HTTP {resp.status_code}, sleep {wait}s")
                interruptible_sleep(wait)
                continue
            if 500 <= resp.status_code < 600:
                wait = min(BASE_RETRY_DELAY * (2 ** (attempt - 1)) + random.random(), 60)
                thread_safe_print(f"[retry] {description or url}: HTTP {resp.status_code}, sleep {wait:.1f}s")
                interruptible_sleep(wait)
                continue
            resp.raise_for_status()
            return resp
        except RequestException as exc:
            last_exc = exc
            if attempt == MAX_RETRIES:
                break
            wait = min(BASE_RETRY_DELAY * attempt + random.random(), 30)
            thread_safe_print(
                f"[retry] {description or url}: attempt {attempt}/{MAX_RETRIES} failed: {exc}; sleep {wait:.1f}s"
            )
            interruptible_sleep(wait)
    raise RuntimeError(f"request failed after {MAX_RETRIES} attempts: {description or url}") from last_exc


# ---------------------------------------------------------------------------
# Java source recovery
# ---------------------------------------------------------------------------

JAVA_HINTS = [
    " class ",
    " interface ",
    " enum ",
    " record ",
    "public static void main",
    "import java.",
    "import javax.",
    "package ",
]

TYPE_DECL_PATTERN = re.compile(
    r"\b(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed|strictfp)\s+)*"
    r"(class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*)[^{;]*\{",
    re.DOTALL,
)

SOURCE_BLOCK_PATTERN = re.compile(
    r"-+\s*BEGIN\s+SOURCE\s*-+\s*(.*?)\s*-+\s*END\s+SOURCE\s*-+",
    re.DOTALL | re.IGNORECASE,
)

MARKDOWN_CODE_BLOCK_PATTERN = re.compile(
    r"```(?:java)?[ \t]*\n(.*?)\n```",
    re.DOTALL | re.IGNORECASE,
)

GITHUB_JAVA_ATTACHMENT_PATTERN = re.compile(
    r"https://github\.com/user-attachments/files/\d+/[^\s)>\]]+\.java(?:\?[^\s)>\]]+)?",
    re.IGNORECASE,
)


def looks_like_java(text: str) -> bool:
    if not text or len(text.strip()) < 20:
        return False
    padded = f" {text} "
    return any(hint in padded for hint in JAVA_HINTS) or bool(TYPE_DECL_PATTERN.search(text))


def _matching_brace(text: str, open_idx: int) -> Optional[int]:
    """Find the matching closing brace while ignoring comments and literals."""
    depth = 0
    i = open_idx
    state = "code"
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if text.startswith('"""', i):
                state = "textblock"
                i += 3
                continue
            if ch == "/" and nxt == "/":
                state = "line_comment"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block_comment"
                i += 2
                continue
            if ch == '"':
                state = "string"
                i += 1
                continue
            if ch == "'":
                state = "char"
                i += 1
                continue
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return i
            i += 1
            continue
        if state == "line_comment":
            if ch == "\n":
                state = "code"
            i += 1
            continue
        if state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                i += 2
            else:
                i += 1
            continue
        if state in ("string", "char"):
            if ch == "\\":
                i += 2
                continue
            if (state == "string" and ch == '"') or (state == "char" and ch == "'"):
                state = "code"
            i += 1
            continue
        if state == "textblock":
            if text.startswith('"""', i):
                state = "code"
                i += 3
            else:
                i += 1
            continue
    return None


def is_complete_java_file(text: str) -> bool:
    if not looks_like_java(text):
        return False
    for m in TYPE_DECL_PATTERN.finditer(text):
        open_idx = text.find("{", m.start())
        if open_idx >= 0 and _matching_brace(text, open_idx) is not None:
            return True
    return False


def validate_java_content(text: str) -> Tuple[bool, str]:
    if not text or not text.strip():
        return False, "empty"
    head = text.lstrip()[:500].lower()
    if head.startswith("<!doctype") or head.startswith("<html") or "<head>" in head:
        return False, "html"
    if not is_complete_java_file(text):
        return False, "not-structurally-complete-java"
    return True, "valid"


def class_names_from_code(code: str) -> List[str]:
    return [m.group(2) for m in TYPE_DECL_PATTERN.finditer(code)]


def _extend_to_java_preamble(text: str, start_idx: int) -> int:
    line_start = text.rfind("\n", 0, start_idx) + 1
    prefix = text[:line_start]
    lines = prefix.splitlines(keepends=True)
    include_from = len(lines)
    for idx in range(len(lines) - 1, -1, -1):
        stripped = lines[idx].strip()
        if not stripped or stripped.startswith(("package ", "import ", "//", "/*", "*", "*/", "@")):
            include_from = idx
            continue
        break
    return sum(len(x) for x in lines[:include_from]) if include_from < len(lines) else line_start


def extract_source_blocks(text: str) -> List[str]:
    return [m.group(1) for m in SOURCE_BLOCK_PATTERN.finditer(text or "")]


def extract_markdown_blocks(text: str) -> List[str]:
    return [m.group(1) for m in MARKDOWN_CODE_BLOCK_PATTERN.finditer(text or "")]


def extract_inline_java(text: str) -> List[str]:
    if not text:
        return []
    out: List[str] = []
    occupied: List[Tuple[int, int]] = []
    for m in TYPE_DECL_PATTERN.finditer(text):
        if any(a <= m.start() < b for a, b in occupied):
            continue
        open_idx = text.find("{", m.start())
        if open_idx < 0:
            continue
        end_idx = _matching_brace(text, open_idx)
        if end_idx is None:
            continue
        start_idx = _extend_to_java_preamble(text, m.start())
        candidate = text[start_idx : end_idx + 1]
        if is_complete_java_file(candidate):
            out.append(candidate)
            occupied.append((start_idx, end_idx + 1))
    return out


def extract_all_text_java(text: str) -> List[str]:
    """Union of all extraction strategies; deduplication happens later."""
    out: List[str] = []
    for block in extract_source_blocks(text):
        if is_complete_java_file(block):
            out.append(block)
    for block in extract_markdown_blocks(text):
        if is_complete_java_file(block):
            out.append(block)
    for block in extract_inline_java(text):
        if is_complete_java_file(block):
            out.append(block)
    return out


def github_java_attachment_urls(text: str) -> List[str]:
    return list(dict.fromkeys(GITHUB_JAVA_ATTACHMENT_PATTERN.findall(text or "")))


def normalize_java_for_hash(text: str) -> str:
    return re.sub(r"\s+", "", text or "")


def source_sha256(text: str) -> str:
    return hashlib.sha256(normalize_java_for_hash(text).encode("utf-8", errors="ignore")).hexdigest()


def safe_filename(filename: str) -> str:
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", filename or "")
    name = name.strip(" .")
    return name[:120] or "unnamed.java"


def choose_java_filename(content: str, fallback: str = "Recovered.java") -> str:
    names = class_names_from_code(content)
    return f"{names[0]}.java" if names else (fallback if fallback.endswith(".java") else fallback + ".java")


@dataclass
class SourceArtifact:
    content: str
    provenance: str
    origin: str = ""
    original_filename: str = ""
    extraction: str = ""
    sha256: str = field(init=False)

    def __post_init__(self) -> None:
        self.sha256 = source_sha256(self.content)

    def to_manifest_dict(self, saved_filename: str) -> Dict:
        return {
            "saved_filename": saved_filename,
            "original_filename": self.original_filename,
            "sha256": self.sha256,
            "provenance": self.provenance,
            "origin": self.origin,
            "extraction": self.extraction,
            "bytes": len(self.content.encode("utf-8", errors="ignore")),
        }


def deduplicate_artifacts(artifacts: Sequence[SourceArtifact]) -> List[SourceArtifact]:
    seen = set()
    out: List[SourceArtifact] = []
    for artifact in artifacts:
        if artifact.sha256 in seen:
            continue
        seen.add(artifact.sha256)
        out.append(artifact)
    return out


def _load_previous_source_manifest(meta_dir: Path) -> List[Dict]:
    path = meta_dir / "source_manifest.json"
    if not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except Exception:
        return []


def save_issue_bundle(issue_dir: Path, issue_id: str, issue_json: Dict, artifacts: Sequence[SourceArtifact]) -> List[Dict]:
    """
    Save Java files directly under <issue_dir>/, not under a sources/ subdirectory.

    Auxiliary crawler metadata stays in .meta/. Before overwriting, only Java files
    listed in the previous crawler manifest are removed; unrelated user files are not
    touched.
    """
    issue_dir.mkdir(parents=True, exist_ok=True)
    meta_dir = issue_dir / ".meta"
    meta_dir.mkdir(parents=True, exist_ok=True)

    for item in _load_previous_source_manifest(meta_dir):
        old_name = item.get("saved_filename") if isinstance(item, dict) else None
        if old_name:
            old_path = issue_dir / old_name
            if old_path.is_file() and old_path.suffix.lower() == ".java":
                try:
                    old_path.unlink()
                except OSError:
                    pass

    manifest: List[Dict] = []
    used_names = set()
    for idx, artifact in enumerate(artifacts):
        preferred = artifact.original_filename or choose_java_filename(
            artifact.content, f"Recovered_{idx}.java"
        )
        preferred = safe_filename(preferred)
        if not preferred.lower().endswith(".java"):
            preferred += ".java"
        stem, suffix = os.path.splitext(preferred)
        candidate = preferred
        counter = 1
        while candidate.lower() in used_names or (issue_dir / candidate).exists():
            candidate = f"{stem}_{counter}{suffix}"
            counter += 1
        used_names.add(candidate.lower())
        (issue_dir / candidate).write_text(artifact.content, encoding="utf-8")
        manifest.append(artifact.to_manifest_dict(candidate))

    issue_json = dict(issue_json)
    issue_json["java_files"] = [x["saved_filename"] for x in manifest]
    issue_json["source_count"] = len(manifest)
    issue_json["crawler_version"] = "typefuzz-crawler-v4"
    issue_json["collected_at"] = utc_now_iso()

    (issue_dir / f"{issue_id}.json").write_text(
        json.dumps(issue_json, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (meta_dir / "source_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return manifest


def is_valid_patch_text(text: str) -> bool:
    """Return True only when text looks like an actual source-code patch."""
    if not isinstance(text, str):
        return False
    stripped = text.strip()
    if not stripped:
        return False

    # Failed/empty API lookups may still return a non-empty JSON payload such
    # as [] or {}. These must never be treated as a successfully saved patch.
    if stripped in ("[]", "{}", "null"):
        return False
    if stripped[:1] in ("[", "{"):
        try:
            payload = json.loads(stripped)
            if isinstance(payload, (list, dict)):
                return False
        except Exception:
            pass

    # GitHub commit/PR patches use "diff --git". Keep support for ordinary
    # unified diffs as well so older crawler outputs remain recognizable.
    if "diff --git " in text:
        return True
    has_old = re.search(r"(?m)^---\s+\S+", text) is not None
    has_new = re.search(r"(?m)^\+\+\+\s+\S+", text) is not None
    return has_old and has_new


def _is_valid_patch_file(path: Path) -> bool:
    if not path.is_file() or path.suffix.lower() not in (".patch", ".diff"):
        return False
    try:
        content = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return False
    return is_valid_patch_text(content)


def has_saved_fixing_patch(issue_dir: Path) -> bool:
    """Return True only when the issue directory contains an actual patch."""
    manifest_path = issue_dir / ".meta" / "fixing_patches.json"
    if manifest_path.exists():
        try:
            data = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception:
            data = []
        if isinstance(data, list):
            for item in data:
                if not isinstance(item, dict):
                    continue
                name = item.get("patch_file")
                if name and _is_valid_patch_file(issue_dir / str(name)):
                    return True

    # Older crawler outputs may contain patch/diff files without the v4 manifest.
    if issue_dir.is_dir():
        for path in issue_dir.iterdir():
            if _is_valid_patch_file(path):
                return True
    return False



def update_saved_fix_metadata(
    issue_dir: Path,
    issue_id: str,
    fixes: Sequence[Dict],
    fix_lookup_status: str,
) -> None:
    """Update fix metadata in an already-saved issue JSON without touching sources."""
    issue_path = issue_dir / f"{issue_id}.json"
    if not issue_path.exists():
        return
    try:
        data = json.loads(issue_path.read_text(encoding="utf-8"))
    except Exception:
        return
    if not isinstance(data, dict):
        return
    data["fix_lookup_status"] = fix_lookup_status
    data["fixing_commits"] = [
        {key: value for key, value in item.items() if key != "patch_text"}
        for item in fixes
        if isinstance(item, dict)
    ]
    data["collected_at"] = utc_now_iso()
    issue_path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def save_fixing_patches(issue_dir: Path, fixes: Sequence[Dict]) -> List[Dict]:
    """
    Save best-effort fixing patches directly under the issue directory.

    Each input item may contain a private transient key ``patch_text``. The returned
    metadata omits that large field and adds ``patch_file`` when a patch was written.
    Existing crawler-owned patch files from the previous manifest are removed first.
    """
    issue_dir.mkdir(parents=True, exist_ok=True)
    meta_dir = issue_dir / ".meta"
    meta_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = meta_dir / "fixing_patches.json"

    if manifest_path.exists():
        try:
            old = json.loads(manifest_path.read_text(encoding="utf-8"))
            if isinstance(old, list):
                for item in old:
                    if not isinstance(item, dict):
                        continue
                    name = item.get("patch_file")
                    if name:
                        path = issue_dir / name
                        if path.is_file() and path.suffix.lower() in (".patch", ".diff"):
                            try:
                                path.unlink()
                            except OSError:
                                pass
        except Exception:
            pass

    out: List[Dict] = []
    used_names = set()
    for idx, fix in enumerate(fixes):
        item = dict(fix)
        patch_text = item.pop("patch_text", "") or ""
        patch_file = ""
        valid_patch = is_valid_patch_text(patch_text)
        if patch_text.strip() and not valid_patch:
            item["patch_status"] = "invalid_content"
            item.setdefault(
                "patch_error",
                "Response did not contain a Git/unified source patch.",
            )
        if valid_patch:
            repo = str(item.get("repository") or "repo").replace("/", "-")
            sha = str(item.get("sha") or item.get("merge_commit_sha") or "")[:12]
            pr = item.get("pull_request")
            if pr:
                base = f"fix_{repo}_pr{pr}"
            elif sha:
                base = f"fix_{repo}_{sha}"
            else:
                base = f"fix_{repo}_{idx}"
            candidate = safe_filename(base + ".patch")
            stem, suffix = os.path.splitext(candidate)
            counter = 1
            while candidate.lower() in used_names or (issue_dir / candidate).exists():
                candidate = f"{stem}_{counter}{suffix}"
                counter += 1
            used_names.add(candidate.lower())
            (issue_dir / candidate).write_text(patch_text, encoding="utf-8", errors="ignore")
            patch_file = candidate
        item["patch_file"] = patch_file or None
        out.append(item)

    manifest_path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return out


class CrawlState:
    def __init__(self, path: Path):
        self.path = path
        self.data: Dict = {"version": 4, "issues": {}}
        if path.exists():
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    self.data.update(loaded)
                    self.data.setdefault("issues", {})
            except Exception:
                pass

    def status(self, issue_id: str) -> Optional[str]:
        item = self.data["issues"].get(str(issue_id))
        return item.get("status") if isinstance(item, dict) else None

    def should_skip(self, issue_id: str, retry_no_source: bool = False) -> bool:
        status = self.status(issue_id)
        if status == "saved":
            return True
        if status == "no_source" and not retry_no_source:
            return True
        return False

    def set(self, issue_id: str, status: str, **extra) -> None:
        with STATE_LOCK:
            self.data["issues"][str(issue_id)] = {"status": status, "updated_at": utc_now_iso(), **extra}
            self.save()

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self.path)


class PatchSearchCache:
    """
    Persistent fixing-patch cache shared across crawler runs and root issues.

    The issues map stores completed root-level searches. The nodes map stores
    reusable analysis for arbitrary issue nodes reached while traversing a
    relation graph. Deleting this single file forces both layers to be rebuilt
    without disturbing the collected source corpus.
    """

    FLUSH_EVERY = 25

    def __init__(self, path: Path):
        self.path = path
        self.data: Dict = {"version": 2, "issues": {}, "nodes": {}}
        self._node_locks = {}
        self._dirty_updates = 0
        if path.exists():
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    self.data.update(loaded)
            except Exception:
                pass
        self.data.setdefault("issues", {})
        self.data.setdefault("nodes", {})
        self.data["version"] = 2

    def get(self, issue_id: str) -> Optional[Dict]:
        with PATCH_CACHE_LOCK:
            item = self.data.get("issues", {}).get(str(issue_id))
            return dict(item) if isinstance(item, dict) else None

    def has_completed_search(self, issue_id: str) -> bool:
        item = self.get(issue_id)
        return bool(item and item.get("status") in ("not_found", "found"))

    def mark_completed(
        self,
        issue_id: str,
        *,
        status: str,
        patch_count: int,
    ) -> None:
        if status not in ("not_found", "found"):
            return
        with PATCH_CACHE_LOCK:
            self.data.setdefault("issues", {})[str(issue_id)] = {
                "status": status,
                "patch_count": int(patch_count),
                "searched_at": utc_now_iso(),
            }
            self._mark_dirty_unlocked()

    def get_node(self, issue_id: str) -> Optional[Dict]:
        """Return cached node analysis, if this issue has been inspected."""
        with PATCH_CACHE_LOCK:
            item = self.data.get("nodes", {}).get(str(issue_id))
            return dict(item) if isinstance(item, dict) else None

    def set_node(
        self,
        issue_id: str,
        *,
        is_bug: bool,
        related_issues: Sequence[Tuple[str, str]],
        commit_links: Sequence[Dict],
    ) -> None:
        """Persist lightweight relation and commit-link analysis for one node."""
        safe_commits = []
        for item in commit_links:
            if not isinstance(item, dict):
                continue
            safe_commits.append({
                key: value
                for key, value in item.items()
                if key != "patch_text"
            })

        safe_related = [
            {"key": str(key), "relation": str(relation)}
            for key, relation in related_issues
            if str(key)
        ]

        with PATCH_CACHE_LOCK:
            self.data.setdefault("nodes", {})[str(issue_id)] = {
                "is_bug": bool(is_bug),
                "related_issues": safe_related,
                "commit_links": safe_commits,
                "checked_at": utc_now_iso(),
            }
            self._mark_dirty_unlocked()

    def node_lock(self, issue_id: str):
        """
        Return a per-node lock.

        Multiple root issues often reach the same related JBS node at once.
        Serializing the first cache miss prevents duplicate network requests
        within the same multi-worker run.
        """
        key = str(issue_id)
        with PATCH_CACHE_LOCK:
            lock = self._node_locks.get(key)
            if lock is None:
                lock = threading.Lock()
                self._node_locks[key] = lock
            return lock

    def _mark_dirty_unlocked(self) -> None:
        self._dirty_updates += 1
        if self._dirty_updates >= self.FLUSH_EVERY:
            self._save_unlocked()

    def _save_unlocked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(
            json.dumps(self.data, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        os.replace(tmp, self.path)
        self._dirty_updates = 0

    def flush(self) -> None:
        """Persist pending cache updates; safe to call on normal or Ctrl-C exit."""
        with PATCH_CACHE_LOCK:
            if self._dirty_updates or not self.path.exists():
                self._save_unlocked()

    def save(self) -> None:
        """Backward-compatible alias for an explicit cache flush."""
        self.flush()


class SourceHashIndex:
    """Cross-issue duplicate audit. Duplicate issues are not automatically deleted."""

    def __init__(self, path: Path):
        self.path = path
        self.data: Dict[str, List[Dict[str, str]]] = {}
        if path.exists():
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    self.data = loaded
            except Exception:
                pass

    def prior_occurrences(self, sha256: str, current_issue: str) -> List[Dict[str, str]]:
        return [item for item in self.data.get(sha256, []) if item.get("issue_id") != current_issue]

    def add_manifest(self, issue_id: str, manifest: Sequence[Dict]) -> List[Dict]:
        duplicates: List[Dict] = []
        with INDEX_LOCK:
            for item in manifest:
                sha = item["sha256"]
                prior = self.prior_occurrences(sha, issue_id)
                if prior:
                    duplicates.append({"sha256": sha, "current_file": item["saved_filename"], "matches": prior})
                bucket = self.data.setdefault(sha, [])
                record = {"issue_id": issue_id, "file": item["saved_filename"]}
                if record not in bucket:
                    bucket.append(record)
            self.save()
        return duplicates

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self.path)


def save_duplicate_audit(issue_dir: Path, duplicates: Sequence[Dict]) -> None:
    meta_dir = issue_dir / ".meta"
    meta_dir.mkdir(parents=True, exist_ok=True)
    (meta_dir / "cross_issue_duplicates.json").write_text(
        json.dumps(list(duplicates), ensure_ascii=False, indent=2), encoding="utf-8"
    )
