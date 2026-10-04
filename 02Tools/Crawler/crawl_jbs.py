from __future__ import annotations

import argparse
import json
import os
import re
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import requests

from crawl_common import (
    CrawlCancelled,
    CrawlState,
    PatchSearchCache,
    SourceArtifact,
    SourceHashIndex,
    date_windows,
    deduplicate_artifacts,
    extract_all_text_java,
    get_with_retry,
    has_saved_fixing_patch,
    is_valid_patch_text,
    parse_yyyy_mm_dd,
    save_duplicate_audit,
    save_fixing_patches,
    save_issue_bundle,
    request_stop,
    reset_stop,
    thread_safe_print,
    update_saved_fix_metadata,
    validate_java_content,
)

BUG_SYSTEM_URL = "https://bugs.openjdk.org"
GITHUB_API = "https://api.github.com"


@dataclass(frozen=True)
class JBSDatasetSpec:
    name: str
    output_dir: str
    component: str
    subcomponent: Optional[str] = None

    def base_jql(self) -> str:
        parts = [
            'project = JDK',
            'issuetype = Bug',
            f'component = "{self.component}"',
        ]
        if self.subcomponent:
            parts.append(f'subcomponent = "{self.subcomponent}"')
        return " AND ".join(parts)


class JBSClient:
    def __init__(self, server_url: str = BUG_SYSTEM_URL):
        self.server_url = server_url.rstrip("/")
        self.search_url = f"{self.server_url}/rest/api/2/search"
        self._local = threading.local()

    def _session(self) -> requests.Session:
        if not hasattr(self._local, "session"):
            s = requests.Session()
            s.headers.update({
                "User-Agent": "TypeFuzz-Empirical-Crawler/4.0",
                "Accept": "application/json,text/plain,*/*",
                "Accept-Language": "en-US,en;q=0.9",
            })
            self._local.session = s
        return self._local.session

    def search_page(self, jql: str, start_at: int, max_results: int) -> Dict:
        resp = get_with_retry(
            self._session(),
            self.search_url,
            params={
                "jql": jql,
                "startAt": start_at,
                "maxResults": max_results,
                "fields": "key,created,updated",
            },
            description=f"JBS search startAt={start_at}",
        )
        return resp.json()

    def earliest_issue_date(self, spec: JBSDatasetSpec) -> date:
        data = self.search_page(
            f"{spec.base_jql()} ORDER BY created ASC, key ASC",
            start_at=0,
            max_results=1,
        )
        issues = data.get("issues", []) or []
        if not issues:
            raise RuntimeError(f"No JBS issues found for sampling frame: {spec.base_jql()}")
        created = ((issues[0].get("fields") or {}).get("created") or "")[:10]
        if not created:
            raise RuntimeError("Earliest JBS issue has no created date")
        return parse_yyyy_mm_dd(created)

    def fetch_issue(self, key: str) -> Dict:
        fields = (
            "summary,status,resolution,priority,labels,created,updated,resolutiondate,"
            "description,attachment,components,issuelinks,issuetype"
        )
        resp = get_with_retry(
            self._session(),
            f"{self.server_url}/rest/api/2/issue/{key}",
            params={"fields": fields},
            description=f"JBS issue {key}",
        )
        return resp.json()

    def fetch_issue_links(self, key: str) -> Dict:
        """Fetch only fields required by patch-graph traversal."""
        resp = get_with_retry(
            self._session(),
            f"{self.server_url}/rest/api/2/issue/{key}",
            params={"fields": "issuelinks,issuetype"},
            description=f"JBS issue links {key}",
        )
        return resp.json()

    def fetch_remote_links(self, key: str) -> List[Dict]:
        """Return the remote links shown in the JBS Issue Links section."""
        resp = get_with_retry(
            self._session(),
            f"{self.server_url}/rest/api/2/issue/{key}/remotelink",
            description=f"JBS remote links {key}",
        )
        data = resp.json()
        return data if isinstance(data, list) else []


    def fetch_all_comments(self, key: str) -> List[Dict]:
        url = f"{self.server_url}/rest/api/2/issue/{key}/comment"
        start_at = 0
        comments: List[Dict] = []
        while True:
            resp = get_with_retry(
                self._session(),
                url,
                params={"startAt": start_at, "maxResults": 100},
                description=f"JBS comments {key} startAt={start_at}",
            )
            data = resp.json()
            batch = data.get("comments", []) or []
            comments.extend(batch)
            total = int(data.get("total", len(comments)))
            start_at += len(batch)
            if not batch or start_at >= total:
                break
        return comments

    def download_java_attachment(self, url: str, filename: str) -> Optional[str]:
        resp = get_with_retry(
            self._session(),
            url,
            headers={"Referer": f"{self.server_url}/"},
            description=f"JBS attachment {filename}",
        )
        content = resp.text
        ok, _ = validate_java_content(content)
        return content if ok else None


class OpenJDKFixClient:
    """
    Discover fixing commits from OpenJDK JBS Issue Links.

    Patch discovery uses a shared node cache so relation/remote-link analysis is
    performed once per JBS issue, even when many root issues reach the same
    related node. The cache is persisted in patch_search_cache.json.
    """

    # Keep graph expansion bounded. Deep searches are expensive and low-yield;
    # cached nodes make repeated runs cheap, while users can delete the cache
    # when they intentionally want a fresh traversal.
    MAX_RELATED_ISSUES = 10

    def __init__(
        self,
        jbs_client: JBSClient,
        patch_cache: PatchSearchCache,
        token: str = "",
    ):
        self.jbs_client = jbs_client
        self.patch_cache = patch_cache
        self.token = token
        self._local = threading.local()

    def _session(self) -> requests.Session:
        if not hasattr(self._local, "session"):
            s = requests.Session()
            headers = {
                "User-Agent": "TypeFuzz-Empirical-Crawler/4.0",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            }
            if self.token:
                headers["Authorization"] = f"Bearer {self.token}"
            s.headers.update(headers)
            self._local.session = s
        return self._local.session

    @staticmethod
    def _parse_commit_link(link: Dict) -> Optional[Dict]:
        obj = link.get("object") or {}
        url = str(obj.get("url") or "").strip()
        title = str(obj.get("title") or "").strip()
        relationship = str(link.get("relationship") or "").strip()

        patterns = (
            r"https?://git\.openjdk\.org/([^/]+)/commit/([0-9a-f]{7,40})",
            r"https?://github\.com/openjdk/([^/]+)/commit/([0-9a-f]{7,40})",
            r"https?://git\.openjdk\.java\.net/([^/]+)/commit/([0-9a-f]{7,40})",
        )
        for pattern in patterns:
            match = re.search(pattern, url, re.IGNORECASE)
            if match:
                repo_name, sha = match.groups()
                return {
                    "repository": f"openjdk/{repo_name}",
                    "sha": sha,
                    "html_url": url,
                    "relationship": relationship,
                    "link_title": title,
                }

        title_match = re.search(
            r"\bopenjdk/([A-Za-z0-9_.-]+)/([0-9a-f]{7,40})\b",
            title,
            re.IGNORECASE,
        )
        if title_match:
            repo_name, sha = title_match.groups()
            return {
                "repository": f"openjdk/{repo_name}",
                "sha": sha,
                "html_url": url or f"https://git.openjdk.org/{repo_name}/commit/{sha}",
                "relationship": relationship,
                "link_title": title,
            }
        return None

    @staticmethod
    def _linked_issue_keys(issue_data: Dict) -> List[Tuple[str, str]]:
        """Return JDK issues connected through relates-to or duplicate links."""
        result: List[Tuple[str, str]] = []
        fields = issue_data.get("fields") or {}
        for link in fields.get("issuelinks", []) or []:
            if not isinstance(link, dict):
                continue
            link_type = link.get("type") or {}
            for side, relation_field in (
                ("outwardIssue", "outward"),
                ("inwardIssue", "inward"),
            ):
                target = link.get(side) or {}
                key = str(target.get("key") or "").strip()
                relation = str(
                    link_type.get(relation_field)
                    or link_type.get("name")
                    or ""
                ).strip()
                relation_lower = relation.lower()
                if not key.startswith("JDK-"):
                    continue
                if "relates to" in relation_lower or "duplicate" in relation_lower:
                    result.append((key, relation))
        return result

    @staticmethod
    def _is_bug(issue_data: Dict) -> bool:
        fields = issue_data.get("fields") or {}
        issue_type = fields.get("issuetype") or {}
        return str(issue_type.get("name") or "").strip().lower() == "bug"

    @staticmethod
    def _node_related(node: Dict) -> List[Tuple[str, str]]:
        result: List[Tuple[str, str]] = []
        for item in node.get("related_issues", []) or []:
            if not isinstance(item, dict):
                continue
            key = str(item.get("key") or "").strip()
            relation = str(item.get("relation") or "").strip()
            if key:
                result.append((key, relation))
        return result

    @staticmethod
    def _node_commits(node: Dict) -> List[Dict]:
        return [
            dict(item)
            for item in (node.get("commit_links", []) or [])
            if isinstance(item, dict)
        ]

    def _analyze_node(
        self,
        issue_id: str,
        issue_data: Optional[Dict] = None,
    ) -> Tuple[Dict, bool]:
        """
        Return lightweight cached analysis for one JBS node.

        The boolean result indicates whether the result came from cache.
        A per-node lock prevents many workers from issuing the same two JBS
        requests concurrently on the first cache miss.
        """
        cached = self.patch_cache.get_node(issue_id)
        if cached is not None:
            return cached, True

        with self.patch_cache.node_lock(issue_id):
            cached = self.patch_cache.get_node(issue_id)
            if cached is not None:
                return cached, True

            data = issue_data or self.jbs_client.fetch_issue_links(issue_id)
            is_bug = self._is_bug(data)
            related = self._linked_issue_keys(data)

            commits: List[Dict] = []
            if is_bug:
                for remote_link in self.jbs_client.fetch_remote_links(issue_id):
                    parsed = self._parse_commit_link(remote_link)
                    if parsed:
                        parsed["source_issue"] = issue_id
                        commits.append(parsed)
                commits.sort(
                    key=lambda item: (
                        0 if "master" in str(item.get("link_title") or "").lower() else 1,
                        str(item.get("link_title") or ""),
                    )
                )

            self.patch_cache.set_node(
                issue_id,
                is_bug=is_bug,
                related_issues=related,
                commit_links=commits,
            )
            cached = self.patch_cache.get_node(issue_id)
            if cached is None:
                raise RuntimeError(f"failed to cache patch node {issue_id}")
            return cached, False

    def _discover_from_jbs(
        self,
        issue_id: str,
        issue_data: Dict,
        max_fixes: int,
    ) -> Tuple[List[Dict], bool]:
        root_node, root_hit = self._analyze_node(issue_id, issue_data)
        if root_hit:
            thread_safe_print(f"[patch-node-cache][hit] {issue_id}")

        direct = self._node_commits(root_node)
        if direct:
            for item in direct:
                item["source"] = "jbs-issue-link"
                thread_safe_print(
                    f"[fix-found][root={issue_id}] "
                    f"source={issue_id} "
                    f"commit={item.get('repository')}@{item.get('sha')} "
                    f"path={issue_id}"
                )
            return direct[:max_fixes], True

        fixes: List[Dict] = []
        visited = {issue_id}
        queue: List[Tuple[str, int, List[str]]] = [
            (key, 1, [issue_id, key])
            for key, _ in self._node_related(root_node)
        ]

        # The budget counts only previously unseen nodes that require network
        # access. Cached nodes are effectively free and do not consume it.
        # If the budget is exhausted, the root is left uncached so the next run
        # resumes through the cached prefix and advances to new graph nodes.
        network_misses = 0
        search_complete = True

        while queue and len(fixes) < max_fixes:
            related_key, depth, path = queue.pop(0)
            if related_key in visited:
                continue
            visited.add(related_key)

            cached_before = self.patch_cache.get_node(related_key) is not None
            if (
                not cached_before
                and network_misses >= self.MAX_RELATED_ISSUES
            ):
                queue.insert(0, (related_key, depth, path))
                search_complete = False
                thread_safe_print(
                    f"[fix-search][root={issue_id}] network-node budget "
                    f"reached ({self.MAX_RELATED_ISSUES}); "
                    "search remains resumable"
                )
                break

            try:
                node, cache_hit = self._analyze_node(related_key)
            except Exception as exc:
                thread_safe_print(
                    f"[warn][root={issue_id}] cannot inspect related JBS issue "
                    f"{related_key}: {exc}"
                )
                search_complete = False
                continue

            if not cache_hit:
                network_misses += 1
            else:
                thread_safe_print(
                    f"[patch-node-cache][hit][root={issue_id}] {related_key}"
                )

            if bool(node.get("is_bug")):
                thread_safe_print(
                    f"[fix-link][root={issue_id}] inspect related Bug "
                    f"{related_key} (depth={depth}, "
                    f"path={' -> '.join(path)})"
                )

                related_commits = self._node_commits(node)
                for item in related_commits:
                    item["source"] = "jbs-related-issue-link"
                    item["related_issue"] = related_key
                    item["relation_path"] = path
                    fixes.append(item)
                    thread_safe_print(
                        f"[fix-found][root={issue_id}] "
                        f"source={related_key} "
                        f"commit={item.get('repository')}@{item.get('sha')} "
                        f"depth={depth} path={' -> '.join(path)}"
                    )
                    if len(fixes) >= max_fixes:
                        break
            else:
                thread_safe_print(
                    f"[fix-link][root={issue_id}] traverse non-Bug related "
                    f"issue {related_key} (depth={depth}, "
                    f"path={' -> '.join(path)})"
                )

            for next_key, _ in self._node_related(node):
                if next_key not in visited:
                    queue.append((next_key, depth + 1, path + [next_key]))

        if fixes:
            return fixes[:max_fixes], True
        if queue:
            search_complete = False
        return [], search_complete

    def search_fixes(
        self,
        issue_id: str,
        issue_data: Dict,
        max_fixes: int = 3,
    ) -> Tuple[List[Dict], bool]:
        candidates, search_complete = self._discover_from_jbs(
            issue_id,
            issue_data,
            max_fixes,
        )

        unique: List[Dict] = []
        seen = set()
        for item in candidates:
            key = (item.get("repository"), item.get("sha"))
            if key in seen:
                continue
            seen.add(key)
            unique.append(item)
            if len(unique) >= max_fixes:
                break

        for item in unique:
            repo = item["repository"]
            sha = item["sha"]
            try:
                patch_resp = get_with_retry(
                    self._session(),
                    f"{GITHUB_API}/repos/{repo}/commits/{sha}",
                    headers={"Accept": "application/vnd.github.patch"},
                    description=f"GitHub patch {repo}@{sha[:12]}",
                    min_delay=0.05,
                )
                item["patch_text"] = patch_resp.text
                if is_valid_patch_text(patch_resp.text):
                    item["patch_status"] = "downloaded"
                else:
                    item["patch_status"] = "invalid_content"
                    item["patch_error"] = (
                        "Patch endpoint returned content that is not a Git/unified patch."
                    )
            except Exception as exc:
                item["patch_text"] = ""
                item["patch_status"] = "download_failed"
                item["patch_error"] = str(exc)

        return unique, search_complete

def _comment_to_json(comment: Dict) -> Dict:
    author = comment.get("author") or {}
    return {
        "author": author.get("displayName") or author.get("name") or "",
        "created": (comment.get("created") or "")[:10],
        "updated": (comment.get("updated") or "")[:10],
        "body": comment.get("body") or "",
    }



def _components(fields: Dict) -> List[str]:
    result = []
    for item in fields.get("components", []) or []:
        if isinstance(item, dict):
            result.append(item.get("name") or "")
        else:
            result.append(str(item))
    return [x for x in result if x]



def build_issue_json(
    issue_data: Dict,
    comments: Sequence[Dict],
    spec: JBSDatasetSpec,
    window: Tuple[date, date],
    jql: str,
    fixing_patches: Sequence[Dict],
    fix_lookup_status: str,
) -> Dict:
    fields = issue_data.get("fields", {}) or {}
    resolution = fields.get("resolution") or {}
    priority = fields.get("priority") or {}
    status = fields.get("status") or {}
    key = issue_data.get("key", "unknown")

    public_fix_meta = []
    for fix in fixing_patches:
        item = {k: v for k, v in fix.items() if k != "patch_text"}
        public_fix_meta.append(item)

    return {
        "issue_tracker": "OpenJDK JBS",
        "project": "JDK",
        "dataset": spec.name,
        "key": key,
        "summary": fields.get("summary") or "",
        "status": status.get("name", ""),
        "resolution": resolution.get("name"),
        "priority": priority.get("name"),
        "labels": fields.get("labels", []) or [],
        "components": _components(fields),
        "query_component": spec.component,
        "query_subcomponent": spec.subcomponent,
        "created": (fields.get("created") or "")[:10],
        "updated": (fields.get("updated") or "")[:10],
        "resolutiondate": (fields.get("resolutiondate") or "")[:10] or None,
        "website": f"{BUG_SYSTEM_URL}/browse/{key}",
        "description": fields.get("description") or "",
        "comments": [_comment_to_json(c) for c in comments],
        "attachments": [
            {
                "filename": a.get("filename", ""),
                "mimeType": a.get("mimeType", ""),
                "size": a.get("size"),
                "content": a.get("content", ""),
            }
            for a in fields.get("attachment", []) or []
        ],
        "issue_links": fields.get("issuelinks", []) or [],
        "fix_lookup_status": fix_lookup_status,
        "fixing_commits": public_fix_meta,
        "sampling": {
            "start_date": window[0].isoformat(),
            "end_date": window[1].isoformat(),
            "jql": jql,
        },
    }



def recover_sources(issue_data: Dict, comments: Sequence[Dict], client: JBSClient) -> List[SourceArtifact]:
    fields = issue_data.get("fields", {}) or {}
    artifacts: List[SourceArtifact] = []

    # Attachments + description + comments are all considered.
    for a in fields.get("attachment", []) or []:
        filename = a.get("filename", "") or ""
        if not filename.lower().endswith(".java"):
            continue
        url = a.get("content") or ""
        if not url:
            continue
        try:
            content = client.download_java_attachment(url, filename)
            if content:
                artifacts.append(SourceArtifact(
                    content=content,
                    provenance="attachment",
                    origin=url,
                    original_filename=filename,
                    extraction="direct-java-attachment",
                ))
        except Exception as exc:
            thread_safe_print(f"[warn] attachment failed {filename}: {exc}")

    description = fields.get("description") or ""
    for idx, code in enumerate(extract_all_text_java(description)):
        artifacts.append(SourceArtifact(
            content=code,
            provenance="description",
            origin=f"description:block:{idx}",
            extraction="text-recovery",
        ))

    for cidx, comment in enumerate(comments):
        body = comment.get("body") or ""
        for bidx, code in enumerate(extract_all_text_java(body)):
            artifacts.append(SourceArtifact(
                content=code,
                provenance="comment",
                origin=f"comment:{cidx}:block:{bidx}",
                extraction="text-recovery",
            ))

    return deduplicate_artifacts(artifacts)



def load_local_issue_link_data(
    issue_dir: Path,
    issue_id: str,
) -> Optional[Dict]:
    """
    Reuse Issue Links already stored in the local issue JSON.

    Root corpus entries are JBS Bugs by construction, so this avoids one JBS
    issue-details request during patch-only/backfill runs. Remote commit links
    are still queried (and cached) independently.
    """
    issue_path = issue_dir / f"{issue_id}.json"
    if not issue_path.exists():
        return None
    try:
        data = json.loads(issue_path.read_text(encoding="utf-8"))
    except Exception:
        return None
    if not isinstance(data, dict):
        return None
    links = data.get("issue_links")
    if not isinstance(links, list):
        return None
    return {
        "key": issue_id,
        "fields": {
            "issuelinks": links,
            "issuetype": {"name": "Bug"},
        },
    }


def make_window_jql(spec: JBSDatasetSpec, start: date, end: date) -> str:
    end_exclusive = end + timedelta(days=1)
    return (
        f"{spec.base_jql()} "
        f'AND created >= "{start.isoformat()}" '
        f'AND created < "{end_exclusive.isoformat()}" '
        f"ORDER BY created ASC, key ASC"
    )



def process_issue(
    key: str,
    spec: JBSDatasetSpec,
    window: Tuple[date, date],
    jql: str,
    client: JBSClient,
    fix_client: Optional[OpenJDKFixClient],
    max_fixes: int,
    output_root: Path,
    state: CrawlState,
    hash_index: SourceHashIndex,
    patch_cache: PatchSearchCache,
) -> Tuple[str, str, int, int]:
    try:
        was_saved = state.status(key) == "saved"
        issue_dir = output_root / key

        if was_saved:
            # For patch backfilling, fetch only fields needed by the relation
            # graph. Avoid re-downloading descriptions, attachments, and other
            # source-oriented issue metadata.
            issue_data = (
                load_local_issue_link_data(issue_dir, key)
                or client.fetch_issue_links(key)
            )
            comments: List[Dict] = []
            artifacts: List[SourceArtifact] = []
        else:
            issue_data = client.fetch_issue(key)
            comments = client.fetch_all_comments(key)
            artifacts = recover_sources(issue_data, comments, client)
            if not artifacts:
                state.set(key, "no_source")
                return key, "no_source", 0, 0

        fixing_patches: List[Dict] = []
        if fix_client is None:
            fix_lookup_status = "disabled"
        else:
            try:
                fixing_patches, search_complete = fix_client.search_fixes(
                    key,
                    issue_data,
                    max_fixes=max_fixes,
                )
                if fixing_patches:
                    fix_lookup_status = "found"
                elif search_complete:
                    fix_lookup_status = "not_found"
                else:
                    fix_lookup_status = "partial"
            except Exception as exc:
                fix_lookup_status = "failed"
                thread_safe_print(f"[warn] fixing patch lookup failed {key}: {exc}")

        if artifacts:
            issue_json = build_issue_json(
                issue_data,
                comments,
                spec,
                window,
                jql,
                fixing_patches,
                fix_lookup_status,
            )
            manifest = save_issue_bundle(issue_dir, key, issue_json, artifacts)
            duplicates = hash_index.add_manifest(key, manifest)
            save_duplicate_audit(issue_dir, duplicates)
            source_count = len(manifest)
            duplicate_source_count = len(duplicates)
        else:
            # The issue was already saved with a valid source bundle. Patch
            # backfilling must not depend on re-downloading that source.
            update_saved_fix_metadata(
                issue_dir,
                key,
                fixing_patches,
                fix_lookup_status,
            )
            source_count = len(list(issue_dir.glob("*.java")))
            duplicate_source_count = 0

        saved_fix_meta = save_fixing_patches(issue_dir, fixing_patches)
        patch_count = sum(1 for x in saved_fix_meta if x.get("patch_file"))
        state.set(
            key,
            "saved",
            source_count=source_count,
            duplicate_source_count=duplicate_source_count,
            fixing_patch_count=patch_count,
            fix_lookup_status=fix_lookup_status,
        )

        # Cache only completed lookups. Failed/disabled lookups, and cases
        # where a commit was found but its patch could not be saved, remain
        # retryable on the next run.
        if fix_lookup_status == "not_found":
            patch_cache.mark_completed(
                key,
                status="not_found",
                patch_count=0,
            )
        elif fix_lookup_status == "found" and patch_count > 0:
            patch_cache.mark_completed(
                key,
                status="found",
                patch_count=patch_count,
            )

        return key, "saved", source_count, patch_count
    except Exception as exc:
        state.set(key, "failed", error=str(exc))
        return key, "failed", 0, 0



def process_saved_patch_issue(
    key: str,
    client: JBSClient,
    fix_client: OpenJDKFixClient,
    max_fixes: int,
    output_root: Path,
    state: CrawlState,
    patch_cache: PatchSearchCache,
) -> Tuple[str, str, int]:
    """Refresh only fixing-patch metadata for an already collected issue."""
    issue_dir = output_root / key
    try:
        issue_data = (
            load_local_issue_link_data(issue_dir, key)
            or client.fetch_issue_links(key)
        )
        fixing_patches, search_complete = fix_client.search_fixes(
            key,
            issue_data,
            max_fixes=max_fixes,
        )
        if fixing_patches:
            fix_lookup_status = "found"
        elif search_complete:
            fix_lookup_status = "not_found"
        else:
            fix_lookup_status = "partial"

        update_saved_fix_metadata(
            issue_dir,
            key,
            fixing_patches,
            fix_lookup_status,
        )
        saved_fix_meta = save_fixing_patches(issue_dir, fixing_patches)
        patch_count = sum(
            1 for item in saved_fix_meta if item.get("patch_file")
        )
        source_count = len(list(issue_dir.glob("*.java")))

        state.set(
            key,
            "saved",
            source_count=source_count,
            fixing_patch_count=patch_count,
            fix_lookup_status=fix_lookup_status,
        )

        if fix_lookup_status == "not_found":
            patch_cache.mark_completed(
                key,
                status="not_found",
                patch_count=0,
            )
        elif patch_count > 0:
            patch_cache.mark_completed(
                key,
                status="found",
                patch_count=patch_count,
            )

        return key, fix_lookup_status, patch_count
    except Exception as exc:
        thread_safe_print(
            f"[warn][patch-only] fixing patch lookup failed {key}: {exc}"
        )
        return key, "failed", 0


def run_jbs_patch_enricher(
    spec: JBSDatasetSpec,
    *,
    workers: int = 4,
    max_fixes: int = 3,
) -> None:
    """
    Patch-only second stage for an existing local corpus.

    It scans already collected issue directories, skips issues with a valid
    patch or a completed root-cache entry, and performs no source/comment
    recovery. Delete patch_search_cache.json to force a fresh patch search.
    """
    reset_stop()
    output_root = Path(spec.output_dir)
    state_dir = output_root / ".crawler_state"
    state = CrawlState(state_dir / "state.json")
    patch_cache = PatchSearchCache(state_dir / "patch_search_cache.json")
    client = JBSClient()

    token = os.getenv("GITHUB_TOKEN", "")
    fix_client = OpenJDKFixClient(client, patch_cache, token)

    candidates: List[str] = []
    if output_root.is_dir():
        for issue_dir in sorted(output_root.iterdir()):
            if not issue_dir.is_dir():
                continue
            key = issue_dir.name
            if not key.startswith("JDK-"):
                continue
            if not any(issue_dir.glob("*.java")):
                continue
            if has_saved_fixing_patch(issue_dir):
                continue
            if patch_cache.has_completed_search(key):
                continue
            candidates.append(key)

    thread_safe_print("=" * 72)
    thread_safe_print(f"TypeFuzz JBS patch enrichment :: {spec.name}")
    thread_safe_print(f"Output      : {output_root}")
    thread_safe_print(f"Candidates  : {len(candidates)}")
    thread_safe_print(
        f"Patch cache : {state_dir / 'patch_search_cache.json'}"
    )
    thread_safe_print("=" * 72)

    if not candidates:
        thread_safe_print("No uncached patch-search candidates.")
        return

    stats = {"found": 0, "not_found": 0, "failed": 0}
    pool = ThreadPoolExecutor(max_workers=max(1, workers))
    futures = {
        pool.submit(
            process_saved_patch_issue,
            key,
            client,
            fix_client,
            max_fixes,
            output_root,
            state,
            patch_cache,
        ): key
        for key in candidates
    }

    try:
        for future in as_completed(futures):
            key, status, patch_count = future.result()
            stats[status] = stats.get(status, 0) + 1
            thread_safe_print(
                f"  [{key}] patch_search={status} "
                f"fixing_patches={patch_count}"
            )
    except (KeyboardInterrupt, CrawlCancelled) as exc:
        request_stop()
        for future in futures:
            future.cancel()
        pool.shutdown(wait=False)
        patch_cache.flush()
        thread_safe_print(
            "\n[interrupt] stopping patch enrichment; pending retries cancelled."
        )
        if isinstance(exc, KeyboardInterrupt):
            raise
        raise KeyboardInterrupt from None
    else:
        pool.shutdown(wait=True)
        patch_cache.flush()

    thread_safe_print("\n" + "=" * 72)
    thread_safe_print(f"Patch enrichment done: {stats}")
    thread_safe_print("=" * 72)



def run_jbs_crawler(
    spec: JBSDatasetSpec,
    *,
    start_date: Optional[str],
    end_date: Optional[str],
    window_days: int = 90,
    page_size: int = 200,
    workers: int = 4,
    retry_no_source: bool = False,
    fixes_mode: str = "auto",
    max_fixes: int = 3,
) -> None:
    reset_stop()
    client = JBSClient()
    start = parse_yyyy_mm_dd(start_date) if start_date else client.earliest_issue_date(spec)
    end = parse_yyyy_mm_dd(end_date) if end_date else date.today()

    output_root = Path(spec.output_dir)
    state_dir = output_root / ".crawler_state"
    state = CrawlState(state_dir / "state.json")
    hash_index = SourceHashIndex(state_dir / "source_hash_index.json")
    patch_cache = PatchSearchCache(state_dir / "patch_search_cache.json")

    token = os.getenv("GITHUB_TOKEN", "")
    if fixes_mode == "off":
        fix_client: Optional[OpenJDKFixClient] = None
    else:
        fix_client = OpenJDKFixClient(client, patch_cache, token)
        if not token:
            thread_safe_print(
                "[warning] GITHUB_TOKEN is not set: JBS fix discovery remains enabled, "
                "but GitHub patch downloads use the unauthenticated rate limit."
            )
    stats = {
        "saved": 0,
        "no_source": 0,
        "failed": 0,
        "skipped": 0,
        "patch_cache_hits": 0,
        "patches": 0,
    }

    thread_safe_print("=" * 72)
    thread_safe_print(f"TypeFuzz JBS crawler v4 :: {spec.name}")
    thread_safe_print(f"Output      : {output_root}")
    thread_safe_print(f"Sampling    : {start} .. {end}")
    thread_safe_print(f"Base JQL    : {spec.base_jql()}")
    thread_safe_print(f"Fix lookup  : {'enabled' if fix_client else 'disabled'}")
    if fix_client:
        thread_safe_print("Fix source  : JBS Issue Links / related duplicate issues")
        thread_safe_print(
            f"Patch cache : {state_dir / 'patch_search_cache.json'}"
        )
    thread_safe_print("=" * 72)

    for window in date_windows(start, end, days=window_days):
        jql = make_window_jql(spec, *window)
        thread_safe_print(f"\n[window] {window[0]} .. {window[1]}")
        start_at = 0

        while True:
            try:
                data = client.search_page(jql, start_at, page_size)
            except Exception as exc:
                # Never silently advance past a failed page.
                thread_safe_print(
                    f"[window-abort] page startAt={start_at} failed; "
                    f"this window will be rescanned next run: {exc}"
                )
                break

            issues = data.get("issues", []) or []
            total = int(data.get("total", 0))
            if not issues:
                break

            keys = [x.get("key") for x in issues if x.get("key")]
            todo: List[str] = []
            for key in keys:
                status = state.status(key)
                if (
                    status == "saved"
                    and fix_client is not None
                    and not has_saved_fixing_patch(output_root / key)
                ):
                    if patch_cache.has_completed_search(key):
                        stats["patch_cache_hits"] += 1
                        continue
                    todo.append(key)
                    continue
                if state.should_skip(key, retry_no_source=retry_no_source):
                    stats["skipped"] += 1
                else:
                    todo.append(key)

            if todo:
                pool = ThreadPoolExecutor(max_workers=max(1, workers))
                futures = {
                    pool.submit(
                        process_issue,
                        key,
                        spec,
                        window,
                        jql,
                        client,
                        fix_client,
                        max_fixes,
                        output_root,
                        state,
                        hash_index,
                        patch_cache,
                    ): key
                    for key in todo
                }
                try:
                    for fut in as_completed(futures):
                        key, status, count, patch_count = fut.result()
                        stats[status] = stats.get(status, 0) + 1
                        stats["patches"] += patch_count
                        thread_safe_print(
                            f"  [{key}] {status} sources={count} fixing_patches={patch_count}"
                        )
                except (KeyboardInterrupt, CrawlCancelled) as exc:
                    request_stop()
                    for fut in futures:
                        fut.cancel()
                    pool.shutdown(wait=False)
                    patch_cache.flush()
                    thread_safe_print(
                        "\n[interrupt] stopping crawler; pending retry waits cancelled."
                    )
                    if isinstance(exc, KeyboardInterrupt):
                        raise
                    raise KeyboardInterrupt from None
                else:
                    pool.shutdown(wait=True)

            start_at += len(issues)
            patch_cache.flush()
            thread_safe_print(
                f"[page] {start_at}/{total} saved={stats['saved']} no_source={stats['no_source']} "
                f"failed={stats['failed']} skipped={stats['skipped']} "
                f"patch_cache_hits={stats['patch_cache_hits']} patches={stats['patches']}"
            )
            if start_at >= total:
                break

    patch_cache.flush()
    thread_safe_print("\n" + "=" * 72)
    thread_safe_print(f"Done: {stats}")
    thread_safe_print("=" * 72)



def build_cli(spec: JBSDatasetSpec) -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=f"TypeFuzz crawler v3 for {spec.name}")
    p.add_argument(
        "--start-date",
        default=None,
        help="YYYY-MM-DD. Omit to start from the earliest bug in this sampling frame.",
    )
    p.add_argument(
        "--end-date",
        default=None,
        help="YYYY-MM-DD. Omit to crawl through today.",
    )
    p.add_argument("--window-days", type=int, default=90)
    p.add_argument("--page-size", type=int, default=200)
    p.add_argument("--workers", type=int, default=4)
    p.add_argument(
        "--retry-no-source",
        action="store_true",
        help="Retry issues previously recorded as having no complete Java source.",
    )
    p.add_argument(
        "--fixes",
        choices=("auto", "on", "off"),
        default="auto",
        help=(
            "Fixing-patch lookup from JBS Issue Links: auto/on=attempt lookup; "
            "off=disable. GITHUB_TOKEN is optional and only affects patch-download rate limits."
        ),
    )
    p.add_argument("--max-fixes", type=int, default=3)
    p.add_argument(
        "--patch-only",
        action="store_true",
        help=(
            "Only enrich fixing patches for the existing local corpus; "
            "do not crawl issue sources/comments."
        ),
    )
    return p
