from __future__ import annotations

import argparse
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import requests
from requests.auth import AuthBase
from requests.exceptions import RequestException

from crawl_common import (
    CrawlCancelled,
    CrawlState,
    PatchSearchCache,
    SourceArtifact,
    SourceHashIndex,
    deduplicate_artifacts,
    extract_all_text_java,
    github_java_attachment_urls,
    has_saved_fixing_patch,
    interruptible_sleep,
    is_valid_patch_text,
    parse_yyyy_mm_dd,
    save_duplicate_audit,
    save_fixing_patches,
    save_issue_bundle,
    raise_if_stopping,
    request_stop,
    reset_stop,
    thread_safe_print,
    update_saved_fix_metadata,
    validate_java_content,
)


GITHUB_API = "https://api.github.com"


class BearerAuth(AuthBase):
    """
    Explicit GitHub Bearer authentication.

    Using Session.auth instead of only placing Authorization in session.headers
    prevents requests from replacing the token with credentials from ~/.netrc,
    while leaving Session.trust_env=True so http_proxy/https_proxy still work.
    """

    def __init__(self, token: str):
        self.token = token

    def __call__(self, request):
        request.headers["Authorization"] = f"Bearer {self.token}"
        return request


@dataclass(frozen=True)
class GitHubDatasetSpec:
    """
    Configuration for a GitHub issue corpus.

    Example:
        repository="eclipse-openj9/openj9"
        labels=("comp:jit",)
        issue_prefix="OpenJ9"
    """

    name: str
    repository: str
    output_dir: str
    labels: Tuple[str, ...] = field(default_factory=tuple)
    issue_prefix: str = "Issue"

    def base_query(self) -> str:
        parts = [
            f"repo:{self.repository}",
            "is:issue",
        ]
        for label in self.labels:
            escaped = label.replace('"', '\\"')
            parts.append(f'label:"{escaped}"')
        return " ".join(parts)

    def issue_id(self, issue_number: int) -> str:
        return f"{self.issue_prefix}-{issue_number}"



class GitHubClient:
    def __init__(self, spec: GitHubDatasetSpec, token: str = ""):
        self.spec = spec
        self.session = requests.Session()

        # Keep trust_env=True (requests default), so server-side
        # http_proxy / https_proxy environment variables continue to work.
        self.session.headers.update(
            {
                "User-Agent": "TypeFuzz-Empirical-Crawler/4.2",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            }
        )

        # Explicit Session.auth prevents ~/.netrc from replacing the Bearer
        # token, while still preserving proxy configuration from the environment.
        if token:
            self.session.auth = BearerAuth(token)

    def _request(
        self,
        url: str,
        *,
        params: Optional[dict] = None,
        headers: Optional[dict] = None,
        description: str = "",
        min_delay: float = 0.0,
        max_retries: int = 8,
    ) -> requests.Response:
        """
        GitHub-aware GET wrapper.

        Handles:
          - primary/search rate limits via X-RateLimit-Remaining/Reset;
          - secondary rate limits via Retry-After or >=60s backoff;
          - transient 5xx/network errors;
          - proxy environments (through requests.Session trust_env=True).

        Authentication failures (401) are not retried blindly.
        """
        last_exc: Optional[Exception] = None

        for attempt in range(1, max_retries + 1):
            raise_if_stopping()
            if min_delay > 0:
                interruptible_sleep(min_delay)

            try:
                raise_if_stopping()
                resp = self.session.get(
                    url,
                    params=params,
                    headers=headers,
                    timeout=60,
                )
            except RequestException as exc:
                last_exc = exc
                if attempt >= max_retries:
                    break
                wait = min(2 ** attempt, 30)
                thread_safe_print(
                    f"[retry] {description or url}: network error "
                    f"({attempt}/{max_retries}): {exc}; sleep {wait}s"
                )
                interruptible_sleep(wait)
                continue

            if resp.status_code == 401:
                raise RuntimeError(
                    f"GitHub authentication failed (401) for "
                    f"{description or url}. Check GITHUB_TOKEN."
                )

            if resp.status_code in (403, 429):
                remaining = resp.headers.get("X-RateLimit-Remaining")
                reset = resp.headers.get("X-RateLimit-Reset")
                retry_after = resp.headers.get("Retry-After")
                resource = resp.headers.get("X-RateLimit-Resource", "unknown")

                wait = None
                reason = "rate limited"

                # Primary/search rate limit exhausted.
                if remaining == "0" and reset:
                    try:
                        wait = max(int(reset) - int(time.time()) + 2, 1)
                        reason = f"{resource} rate limit exhausted"
                    except ValueError:
                        wait = None

                # Secondary limit often supplies Retry-After.
                if wait is None and retry_after:
                    try:
                        wait = max(int(float(retry_after)) + 1, 1)
                        reason = "secondary rate limit"
                    except ValueError:
                        wait = None

                # GitHub recommends waiting at least one minute for a
                # secondary limit when Retry-After is not supplied.
                if wait is None:
                    wait = min(max(60, 2 ** attempt * 10), 300)
                    reason = "403/429 secondary throttling"

                thread_safe_print(
                    f"[rate-limit] {description or url}: {reason}; "
                    f"remaining={remaining}, reset={reset}, sleep {wait}s"
                )
                interruptible_sleep(wait)
                continue

            if 500 <= resp.status_code < 600:
                if attempt >= max_retries:
                    resp.raise_for_status()
                wait = min(2 ** attempt, 60)
                thread_safe_print(
                    f"[retry] {description or url}: HTTP {resp.status_code} "
                    f"({attempt}/{max_retries}); sleep {wait}s"
                )
                interruptible_sleep(wait)
                continue

            resp.raise_for_status()
            return resp

        raise RuntimeError(
            f"request failed after {max_retries} attempts: "
            f"{description or url}"
        ) from last_exc

    def earliest_issue_date(self) -> date:
        resp = self._request(
            f"{GITHUB_API}/search/issues",
            params={
                "q": self.spec.base_query(),
                "per_page": 1,
                "page": 1,
                "sort": "created",
                "order": "asc",
            },
            description=f"{self.spec.name} earliest issue",
            min_delay=0.2,
        )
        items = resp.json().get("items", []) or []
        if not items:
            raise RuntimeError(
                f"No issues found for GitHub query: {self.spec.base_query()}"
            )

        created = (items[0].get("created_at") or "")[:10]
        if not created:
            raise RuntimeError(
                f"Earliest issue for {self.spec.name} has no created_at"
            )
        return parse_yyyy_mm_dd(created)

    def search_window(self, start: date, end: date, page: int = 1) -> Dict:
        query = (
            f"{self.spec.base_query()} "
            f"created:{start.isoformat()}..{end.isoformat()}"
        )
        resp = self._request(
            f"{GITHUB_API}/search/issues",
            params={
                "q": query,
                "per_page": 100,
                "page": page,
                "sort": "created",
                "order": "asc",
            },
            description=(
                f"{self.spec.name} search "
                f"{start.isoformat()}..{end.isoformat()} page={page}"
            ),
            min_delay=0.25,
        )
        return resp.json()

    def fetch_comments(self, issue_number: int) -> List[Dict]:
        url = (
            f"{GITHUB_API}/repos/{self.spec.repository}/issues/"
            f"{issue_number}/comments"
        )
        params: Optional[dict] = {"per_page": 100}
        comments: List[Dict] = []

        while url:
            resp = self._request(
                url,
                params=params,
                description=f"{self.spec.name} comments #{issue_number}",
                min_delay=0.2,
            )
            comments.extend(resp.json() or [])
            url = resp.links.get("next", {}).get("url")
            params = None

        return comments

    def fetch_timeline(self, issue_number: int) -> List[Dict]:
        url = (
            f"{GITHUB_API}/repos/{self.spec.repository}/issues/"
            f"{issue_number}/timeline"
        )
        params: Optional[dict] = {"per_page": 100}
        events: List[Dict] = []

        while url:
            resp = self._request(
                url,
                params=params,
                headers={"Accept": "application/vnd.github+json"},
                description=f"{self.spec.name} timeline #{issue_number}",
                min_delay=0.2,
            )
            events.extend(resp.json() or [])
            url = resp.links.get("next", {}).get("url")
            params = None

        return events

    def fetch_pull_request(self, repo: str, number: int) -> Dict:
        resp = self._request(
            f"{GITHUB_API}/repos/{repo}/pulls/{number}",
            description=f"GitHub PR {repo}#{number}",
            min_delay=0.15,
        )
        return resp.json()

    def fetch_pull_patch(self, repo: str, number: int) -> str:
        resp = self._request(
            f"{GITHUB_API}/repos/{repo}/pulls/{number}",
            headers={"Accept": "application/vnd.github.patch"},
            description=f"GitHub PR patch {repo}#{number}",
            min_delay=0.15,
        )
        return resp.text

    def fetch_commit_patch(self, repo: str, sha: str) -> str:
        resp = self._request(
            f"{GITHUB_API}/repos/{repo}/commits/{sha}",
            headers={"Accept": "application/vnd.github.patch"},
            description=f"GitHub commit patch {repo}@{sha[:12]}",
            min_delay=0.15,
        )
        return resp.text

    def download_java(self, url: str) -> Optional[str]:
        resp = self._request(
            url,
            description=f"GitHub Java attachment {url}",
            min_delay=0.2,
        )
        content = resp.text
        ok, _ = validate_java_content(content)
        return content if ok else None


def split_window_if_needed(
    client: GitHubClient,
    start: date,
    end: date,
    *,
    safe_limit: int = 900,
) -> List[Tuple[date, date]]:
    """
    GitHub Search exposes at most the first 1000 results of one query.
    Recursively split date windows before paging when the result count is high.
    """

    first = client.search_window(start, end, page=1)
    total = int(first.get("total_count", 0))

    if total <= safe_limit:
        return [(start, end)]

    if start >= end:
        raise RuntimeError(
            f"GitHub search has {total} issues on one day ({start}); "
            "cannot safely enumerate the query through the 1000-result Search cap."
        )

    span = (end - start).days
    mid = start + timedelta(days=span // 2)

    return (
        split_window_if_needed(
            client,
            start,
            mid,
            safe_limit=safe_limit,
        )
        + split_window_if_needed(
            client,
            mid + timedelta(days=1),
            end,
            safe_limit=safe_limit,
        )
    )


def _comment_json(comment: Dict) -> Dict:
    user = comment.get("user") or {}
    return {
        "author": user.get("login", ""),
        "created": (comment.get("created_at") or "")[:10],
        "updated": (comment.get("updated_at") or "")[:10],
        "body": comment.get("body") or "",
        "html_url": comment.get("html_url") or "",
    }


def _repo_from_api_url(url: Optional[str]) -> Optional[str]:
    if not isinstance(url, str) or not url:
        return None

    marker = "/repos/"
    if marker not in url:
        return None

    tail = url.split(marker, 1)[1].strip("/")
    parts = tail.split("/")
    if len(parts) >= 2:
        return f"{parts[0]}/{parts[1]}"
    return None


def discover_github_fixes(
    spec: GitHubDatasetSpec,
    issue_number: int,
    timeline: Sequence[Dict],
    client: GitHubClient,
    max_fixes: int = 5,
) -> List[Dict]:
    """
    Fixing-patch discovery using links recorded in the GitHub issue timeline.

    A PR explicitly linked from the issue timeline is accepted when it has been
    merged. We intentionally do not require a secondary closing-keyword match:
    the issue-to-PR link itself is the discovery evidence. Direct commit ids
    exposed by timeline events are retained as well.
    """

    pr_refs: List[Tuple[str, int]] = []
    commit_refs: List[Tuple[str, str]] = []

    for event in timeline:
        source = event.get("source") or {}
        source_issue = source.get("issue") or {}

        if source_issue.get("pull_request"):
            repo = (
                _repo_from_api_url(source_issue.get("repository_url") or "")
                or spec.repository
            )
            number = source_issue.get("number")
            if isinstance(number, int):
                pr_refs.append((repo, number))

        commit_id = event.get("commit_id")
        if isinstance(commit_id, str) and commit_id:
            commit_url = event.get("commit_url")
            repo = (
                _repo_from_api_url(
                    commit_url if isinstance(commit_url, str) else None
                )
                or spec.repository
            )
            commit_refs.append((repo, commit_id))

        subject = event.get("subject") or {}
        subject_url = subject.get("url")
        if (
            subject.get("type") == "PullRequest"
            and isinstance(subject_url, str)
            and subject_url
        ):
            repo = _repo_from_api_url(subject_url) or spec.repository
            try:
                number = int(subject_url.rstrip("/").split("/")[-1])
                pr_refs.append((repo, number))
            except (TypeError, ValueError):
                pass

    fixes: List[Dict] = []

    seen_pr = set()
    for repo, pr_number in pr_refs:
        if (repo, pr_number) in seen_pr:
            continue
        seen_pr.add((repo, pr_number))

        try:
            pr = client.fetch_pull_request(repo, pr_number)
        except Exception as exc:
            thread_safe_print(
                f"[warn] cannot inspect candidate fixing PR "
                f"{repo}#{pr_number}: {exc}"
            )
            continue

        if not pr.get("merged_at"):
            continue

        try:
            patch = client.fetch_pull_patch(repo, pr_number)
            if is_valid_patch_text(patch):
                patch_status = "downloaded"
                patch_error = None
            else:
                patch_status = "invalid_content"
                patch_error = (
                    "Patch endpoint returned content that is not a Git/unified patch."
                )
        except Exception as exc:
            patch = ""
            patch_status = "download_failed"
            patch_error = str(exc)

        item = {
            "source": "github-issue-timeline-pr",
            "repository": repo,
            "pull_request": pr_number,
            "sha": pr.get("merge_commit_sha"),
            "merge_commit_sha": pr.get("merge_commit_sha"),
            "title": pr.get("title") or "",
            "merged_at": pr.get("merged_at"),
            "html_url": pr.get("html_url"),
            "patch_status": patch_status,
            "patch_text": patch,
        }
        if patch_error:
            item["patch_error"] = patch_error

        fixes.append(item)
        if len(fixes) >= max_fixes:
            return fixes

    seen_commit = set()
    for repo, sha in commit_refs:
        if (repo, sha) in seen_commit:
            continue
        seen_commit.add((repo, sha))

        if any(
            item.get("sha") == sha
            and item.get("repository") == repo
            for item in fixes
        ):
            continue

        try:
            patch = client.fetch_commit_patch(repo, sha)
            if is_valid_patch_text(patch):
                patch_status = "downloaded"
                patch_error = None
            else:
                patch_status = "invalid_content"
                patch_error = (
                    "Patch endpoint returned content that is not a Git/unified patch."
                )
        except Exception as exc:
            patch = ""
            patch_status = "download_failed"
            patch_error = str(exc)

        item = {
            "source": "github-issue-timeline-commit",
            "repository": repo,
            "sha": sha,
            "html_url": f"https://github.com/{repo}/commit/{sha}",
            "patch_status": patch_status,
            "patch_text": patch,
        }
        if patch_error:
            item["patch_error"] = patch_error

        fixes.append(item)
        if len(fixes) >= max_fixes:
            break

    return fixes


def build_issue_json(
    spec: GitHubDatasetSpec,
    issue: Dict,
    comments: Sequence[Dict],
    window: Tuple[date, date],
    fixing_patches: Sequence[Dict],
    fix_lookup_status: str,
) -> Dict:
    labels = [
        label.get("name", "")
        for label in issue.get("labels", []) or []
        if isinstance(label, dict)
    ]
    closed_at = issue.get("closed_at")

    public_fix_meta = [
        {
            key: value
            for key, value in item.items()
            if key != "patch_text"
        }
        for item in fixing_patches
    ]

    return {
        "issue_tracker": "GitHub",
        "repository": spec.repository,
        "dataset": spec.name,
        "number": issue.get("number"),
        "status": issue.get("state", ""),
        "labels": ", ".join(labels),
        "label_names": labels,
        "created": (issue.get("created_at") or "")[:10],
        "updated": (issue.get("updated_at") or "")[:10],
        "closed": closed_at[:10] if closed_at else "N/A",
        "website": issue.get("html_url", ""),
        "title": issue.get("title") or "",
        "body": issue.get("body") or "",
        "comments": [_comment_json(comment) for comment in comments],
        "fix_lookup_status": fix_lookup_status,
        "fixing_commits": public_fix_meta,
        "sampling": {
            "start_date": window[0].isoformat(),
            "end_date": window[1].isoformat(),
            "query": (
                f"{spec.base_query()} "
                f"created:{window[0].isoformat()}..{window[1].isoformat()}"
            ),
        },
    }


def recover_sources(
    issue: Dict,
    comments: Sequence[Dict],
    client: GitHubClient,
) -> List[SourceArtifact]:
    artifacts: List[SourceArtifact] = []

    body = issue.get("body") or ""
    for idx, code in enumerate(extract_all_text_java(body)):
        artifacts.append(
            SourceArtifact(
                content=code,
                provenance="body",
                origin=f"issue-body:block:{idx}",
                extraction="text-recovery",
            )
        )

    for comment_index, comment in enumerate(comments):
        comment_body = comment.get("body") or ""
        for block_index, code in enumerate(
            extract_all_text_java(comment_body)
        ):
            artifacts.append(
                SourceArtifact(
                    content=code,
                    provenance="comment",
                    origin=(
                        f"comment:{comment_index}:"
                        f"block:{block_index}"
                    ),
                    extraction="text-recovery",
                )
            )

    all_text = "\n".join(
        [body]
        + [comment.get("body") or "" for comment in comments]
    )

    for url in github_java_attachment_urls(all_text):
        filename = url.split("/")[-1].split("?")[0]
        try:
            content = client.download_java(url)
            if content:
                artifacts.append(
                    SourceArtifact(
                        content=content,
                        provenance="attachment",
                        origin=url,
                        original_filename=filename,
                        extraction="direct-java-attachment",
                    )
                )
        except Exception as exc:
            thread_safe_print(
                f"[warn] GitHub attachment failed {url}: {exc}"
            )

    return deduplicate_artifacts(artifacts)


def process_issue(
    spec: GitHubDatasetSpec,
    issue: Dict,
    comments: Sequence[Dict],
    client: GitHubClient,
    output_root: Path,
    state: CrawlState,
    hash_index: SourceHashIndex,
    patch_cache: PatchSearchCache,
    window: Tuple[date, date],
    fetch_fixes: bool,
    max_fixes: int,
) -> Tuple[str, str, int, int]:
    number = int(issue.get("number", 0))
    issue_id = spec.issue_id(number)

    try:
        was_saved = state.status(issue_id) == "saved"
        issue_dir = output_root / issue_id
        artifacts = [] if was_saved else recover_sources(issue, comments, client)

        if not artifacts and not was_saved:
            state.set(issue_id, "no_source")
            return issue_id, "no_source", 0, 0

        fixing_patches: List[Dict] = []

        if not fetch_fixes:
            fix_lookup_status = "disabled"
        else:
            try:
                timeline = client.fetch_timeline(number)
                fixing_patches = discover_github_fixes(
                    spec,
                    number,
                    timeline,
                    client,
                    max_fixes=max_fixes,
                )
                fix_lookup_status = (
                    "found" if fixing_patches else "not_found"
                )
            except Exception as exc:
                # Patch lookup failure does not invalidate the source corpus.
                # Because no patch is saved, the issue will be retried on a later run.
                fix_lookup_status = "failed"
                thread_safe_print(
                    f"[warn] fixing patch lookup failed "
                    f"{issue_id}: {exc}"
                )

        if artifacts:
            issue_json = build_issue_json(
                spec,
                issue,
                comments,
                window,
                fixing_patches,
                fix_lookup_status,
            )
            manifest = save_issue_bundle(
                issue_dir,
                issue_id,
                issue_json,
                artifacts,
            )
            duplicates = hash_index.add_manifest(
                issue_id,
                manifest,
            )
            save_duplicate_audit(
                issue_dir,
                duplicates,
            )
            source_count = len(manifest)
            duplicate_source_count = len(duplicates)
        else:
            # Preserve the already-saved source bundle while refreshing only
            # the fixing-patch metadata.
            update_saved_fix_metadata(
                issue_dir,
                issue_id,
                fixing_patches,
                fix_lookup_status,
            )
            source_count = len(list(issue_dir.glob("*.java")))
            duplicate_source_count = 0

        saved_fix_meta = save_fixing_patches(
            issue_dir,
            fixing_patches,
        )
        patch_count = sum(
            1
            for item in saved_fix_meta
            if item.get("patch_file")
        )

        state.set(
            issue_id,
            "saved",
            source_count=source_count,
            duplicate_source_count=duplicate_source_count,
            fixing_patch_count=patch_count,
            fix_lookup_status=fix_lookup_status,
        )

        # Cache only completed patch lookups. Failed/disabled lookups, and
        # candidates whose patch download was invalid/failed, stay retryable.
        if fix_lookup_status == "not_found":
            patch_cache.mark_completed(
                issue_id,
                status="not_found",
                patch_count=0,
            )
        elif fix_lookup_status == "found" and patch_count > 0:
            patch_cache.mark_completed(
                issue_id,
                status="found",
                patch_count=patch_count,
            )

        return (
            issue_id,
            "saved",
            source_count,
            patch_count,
        )

    except Exception as exc:
        state.set(
            issue_id,
            "failed",
            error=str(exc),
        )
        return issue_id, "failed", 0, 0


def process_saved_github_patch_issue(
    spec: GitHubDatasetSpec,
    issue_id: str,
    issue_number: int,
    client: GitHubClient,
    output_root: Path,
    state: CrawlState,
    patch_cache: PatchSearchCache,
    max_fixes: int,
) -> Tuple[str, str, int]:
    """Refresh only fixing-patch metadata for an existing GitHub issue."""
    issue_dir = output_root / issue_id
    try:
        timeline = client.fetch_timeline(issue_number)
        fixing_patches = discover_github_fixes(
            spec,
            issue_number,
            timeline,
            client,
            max_fixes=max_fixes,
        )
        fix_lookup_status = "found" if fixing_patches else "not_found"

        update_saved_fix_metadata(
            issue_dir,
            issue_id,
            fixing_patches,
            fix_lookup_status,
        )
        saved_fix_meta = save_fixing_patches(
            issue_dir,
            fixing_patches,
        )
        patch_count = sum(
            1 for item in saved_fix_meta if item.get("patch_file")
        )
        source_count = len(list(issue_dir.glob("*.java")))

        state.set(
            issue_id,
            "saved",
            source_count=source_count,
            fixing_patch_count=patch_count,
            fix_lookup_status=fix_lookup_status,
        )

        if fix_lookup_status == "not_found":
            patch_cache.mark_completed(
                issue_id,
                status="not_found",
                patch_count=0,
            )
        elif patch_count > 0:
            patch_cache.mark_completed(
                issue_id,
                status="found",
                patch_count=patch_count,
            )

        return issue_id, fix_lookup_status, patch_count
    except Exception as exc:
        thread_safe_print(
            f"[warn][patch-only] fixing patch lookup failed "
            f"{issue_id}: {exc}"
        )
        return issue_id, "failed", 0


def run_github_patch_enricher(
    spec: GitHubDatasetSpec,
    *,
    workers: int,
    max_fixes: int,
) -> None:
    """Patch-only second stage for an existing GitHub issue corpus."""
    reset_stop()
    token = os.getenv("GITHUB_TOKEN", "")
    client = GitHubClient(spec, token)

    output_root = Path(spec.output_dir)
    state_dir = output_root / ".crawler_state"
    state = CrawlState(state_dir / "state.json")
    patch_cache = PatchSearchCache(
        state_dir / "patch_search_cache.json"
    )

    candidates: List[Tuple[str, int]] = []
    if output_root.is_dir():
        prefix = f"{spec.issue_prefix}-"
        for issue_dir in sorted(output_root.iterdir()):
            if not issue_dir.is_dir():
                continue
            issue_id = issue_dir.name
            if not issue_id.startswith(prefix):
                continue
            if not any(issue_dir.glob("*.java")):
                continue
            if has_saved_fixing_patch(issue_dir):
                continue
            if patch_cache.has_completed_search(issue_id):
                continue
            try:
                number = int(issue_id[len(prefix):])
            except ValueError:
                continue
            candidates.append((issue_id, number))

    thread_safe_print("=" * 72)
    thread_safe_print(
        f"TypeFuzz GitHub patch enrichment :: {spec.name}"
    )
    thread_safe_print(f"Output       : {output_root}")
    thread_safe_print(f"Candidates   : {len(candidates)}")
    thread_safe_print(
        f"Patch cache  : {state_dir / 'patch_search_cache.json'}"
    )
    thread_safe_print("=" * 72)

    if not candidates:
        thread_safe_print("No uncached patch-search candidates.")
        return

    stats = {"found": 0, "not_found": 0, "failed": 0}
    pool = ThreadPoolExecutor(max_workers=max(1, workers))
    futures = {
        pool.submit(
            process_saved_github_patch_issue,
            spec,
            issue_id,
            number,
            client,
            output_root,
            state,
            patch_cache,
            max_fixes,
        ): issue_id
        for issue_id, number in candidates
    }

    try:
        for future in as_completed(futures):
            issue_id, status, patch_count = future.result()
            stats[status] = stats.get(status, 0) + 1
            thread_safe_print(
                f"  [{issue_id}] patch_search={status} "
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



def run_github_crawler(
    spec: GitHubDatasetSpec,
    *,
    start_date: Optional[str],
    end_date: Optional[str],
    window_days: int,
    workers: int,
    retry_no_source: bool,
    fixes_mode: str,
    max_fixes: int,
) -> None:
    reset_stop()
    token = os.getenv("GITHUB_TOKEN", "")
    client = GitHubClient(spec, token)

    start = (
        parse_yyyy_mm_dd(start_date)
        if start_date
        else client.earliest_issue_date()
    )
    end = (
        parse_yyyy_mm_dd(end_date)
        if end_date
        else date.today()
    )

    if end < start:
        raise ValueError(
            f"end date {end} is earlier than start date {start}"
        )

    if fixes_mode == "off":
        fetch_fixes = False
    else:
        # Timeline-based fix discovery works without authentication. A token
        # only increases the GitHub API rate limit.
        fetch_fixes = True
        if not token:
            thread_safe_print(
                "[warning] GITHUB_TOKEN is not set: fixing-patch lookup remains "
                "enabled, but unauthenticated GitHub API limits apply."
            )

    if not token:
        thread_safe_print(
            "[warning] GITHUB_TOKEN is not set. "
            "Unauthenticated GitHub API limits will apply."
        )

    output_root = Path(spec.output_dir)
    state_dir = output_root / ".crawler_state"

    state = CrawlState(
        state_dir / "state.json"
    )
    hash_index = SourceHashIndex(
        state_dir / "source_hash_index.json"
    )
    patch_cache = PatchSearchCache(
        state_dir / "patch_search_cache.json"
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
    thread_safe_print(
        f"TypeFuzz GitHub crawler :: {spec.name}"
    )
    thread_safe_print(
        f"Repository   : {spec.repository}"
    )
    thread_safe_print(
        f"Query        : {spec.base_query()}"
    )
    thread_safe_print(
        f"Output       : {output_root}"
    )
    thread_safe_print(
        f"Sampling     : {start} .. {end}"
    )
    thread_safe_print(
        f"Fix lookup   : "
        f"{'enabled' if fetch_fixes else 'disabled'}"
    )
    if fetch_fixes:
        thread_safe_print(
            f"Patch cache  : {state_dir / 'patch_search_cache.json'}"
        )
    thread_safe_print("=" * 72)

    coarse_windows: List[Tuple[date, date]] = []
    current = start

    while current <= end:
        window_end = min(
            current + timedelta(days=window_days - 1),
            end,
        )
        coarse_windows.append(
            (current, window_end)
        )
        current = window_end + timedelta(days=1)

    # Process coarse windows progressively:
    #   probe/split one window -> crawl it immediately -> move to next.
    # This avoids firing dozens of GitHub Search requests up front.
    for coarse_window in coarse_windows:
        try:
            windows = split_window_if_needed(
                client,
                *coarse_window,
            )
        except Exception as exc:
            thread_safe_print(
                f"[window-abort] cannot plan {coarse_window[0]}.."
                f"{coarse_window[1]}: {exc}. "
                f"This coarse window will be rescanned next run."
            )
            continue

        for window in windows:
            thread_safe_print(
                f"\n[window] {window[0]} .. {window[1]}"
            )

            page = 1

            while True:
                try:
                    data = client.search_window(
                        *window,
                        page=page,
                    )
                except Exception as exc:
                    # Do not silently move to the next page. On the next run this
                    # window will be scanned again, while completed issue IDs are skipped.
                    thread_safe_print(
                        f"[window-abort] page={page} failed; "
                        f"this window will be rescanned next run: {exc}"
                    )
                    break

                items = data.get("items", []) or []
                total = int(
                    data.get("total_count", 0)
                )

                if not items:
                    break

                todo: List[Dict] = []

                for issue in items:
                    issue_number = int(
                        issue.get("number", 0)
                    )
                    issue_id = spec.issue_id(
                        issue_number
                    )

                    status = state.status(issue_id)
                    if (
                        status == "saved"
                        and fetch_fixes
                        and not has_saved_fixing_patch(
                            output_root / issue_id
                        )
                    ):
                        if patch_cache.has_completed_search(issue_id):
                            stats["patch_cache_hits"] += 1
                        else:
                            # Revisit only saved issues whose patch search has
                            # not yet completed in the persistent cache.
                            todo.append(issue)
                    elif state.should_skip(
                        issue_id,
                        retry_no_source=retry_no_source,
                    ):
                        stats["skipped"] += 1
                    else:
                        todo.append(issue)

                enriched = []

                for issue in todo:
                    issue_number = int(
                        issue.get("number", 0)
                    )
                    issue_id = spec.issue_id(
                        issue_number
                    )

                    if (
                        state.status(issue_id) == "saved"
                        and fetch_fixes
                        and not has_saved_fixing_patch(
                            output_root / issue_id
                        )
                    ):
                        # Existing sources are already available; only the
                        # issue-to-fix links need to be refreshed.
                        comments = []
                    else:
                        try:
                            comments = client.fetch_comments(
                                issue_number
                            )
                        except Exception as exc:
                            state.set(
                                issue_id,
                                "failed",
                                error=f"comment-fetch: {exc}",
                            )
                            stats["failed"] += 1
                            thread_safe_print(
                                f"  [{issue_id}] failed comments"
                            )
                            continue

                    enriched.append(
                        (issue, comments)
                    )

                if enriched:
                    pool = ThreadPoolExecutor(
                        max_workers=max(1, workers)
                    )
                    futures = {
                        pool.submit(
                            process_issue,
                            spec,
                            issue,
                            comments,
                            client,
                            output_root,
                            state,
                            hash_index,
                            patch_cache,
                            window,
                            fetch_fixes,
                            max_fixes,
                        ): issue
                        for issue, comments in enriched
                    }

                    try:
                        for future in as_completed(futures):
                            (
                                issue_id,
                                status,
                                source_count,
                                patch_count,
                            ) = future.result()

                            stats[status] = (
                                stats.get(status, 0) + 1
                            )
                            stats["patches"] += patch_count

                            thread_safe_print(
                                f"  [{issue_id}] {status} "
                                f"sources={source_count} "
                                f"fixing_patches={patch_count}"
                            )
                    except (KeyboardInterrupt, CrawlCancelled) as exc:
                        request_stop()
                        for future in futures:
                            future.cancel()
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

                patch_cache.flush()
                thread_safe_print(
                    f"[page] page={page} total={total} "
                    f"saved={stats['saved']} "
                    f"no_source={stats['no_source']} "
                    f"failed={stats['failed']} "
                    f"skipped={stats['skipped']} "
                    f"patch_cache_hits={stats['patch_cache_hits']} "
                    f"patches={stats['patches']}"
                )

                if (
                    len(items) < 100
                    or page * 100 >= total
                ):
                    break

                page += 1

    patch_cache.flush()
    thread_safe_print(
        "\n" + "=" * 72
    )
    thread_safe_print(
        f"Done: {stats}"
    )
    thread_safe_print(
        "=" * 72
    )


def build_cli(
    spec: GitHubDatasetSpec,
) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            f"TypeFuzz GitHub crawler for {spec.name}"
        )
    )
    parser.add_argument(
        "--start-date",
        default=None,
        help=(
            "YYYY-MM-DD. Omit to start from the earliest "
            "issue matching the dataset query."
        ),
    )
    parser.add_argument(
        "--end-date",
        default=None,
        help=(
            "YYYY-MM-DD. Omit to crawl through today."
        ),
    )
    parser.add_argument(
        "--window-days",
        type=int,
        default=90,
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=2,
    )
    parser.add_argument(
        "--retry-no-source",
        action="store_true",
        help=(
            "Retry issues previously recorded as no_source."
        ),
    )
    parser.add_argument(
        "--fixes",
        choices=("auto", "on", "off"),
        default="auto",
        help=(
            "auto/on=attempt timeline-based fixing-patch lookup; off=disable. "
            "GITHUB_TOKEN is optional and only affects API rate limits."
        ),
    )
    parser.add_argument(
        "--max-fixes",
        type=int,
        default=5,
    )
    parser.add_argument(
        "--patch-only",
        action="store_true",
        help=(
            "Only enrich fixing patches for the existing local corpus; "
            "do not crawl issue sources/comments."
        ),
    )
    return parser
