#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import threading
from dataclasses import dataclass
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from tools.evidence_common import (
    CasePackage,
    build_case_package,
    make_batches,
    valid_case_dirs,
)
from tools.jit_task import (
    SCREEN_PROMPT,
    ANALYZE_PROMPT,
    SCREEN_CFG,
    ANALYZE_CFG,
    validate_screen,
    validate_analyze,
)
from tools.llm_common import (
    LLMClient,
    UsageTracker,
    extract_results,
    log,
    read_json_object,
    result_filename,
    safe_name,
)


WRITE_LOCK = threading.Lock()


@dataclass
class CallOutcome:
    results: Optional[List[Dict[str, Any]]]
    raw: str = ""
    likely_truncated: bool = False


def read_screen_label(case_dir: Path, model_alias: str) -> Optional[str]:
    obj = read_json_object(case_dir / result_filename(model_alias, "screen"))
    label = obj.get("Type_Related") if obj else None
    return label if label in {"Yes", "No", "Unclear"} else None


def select_cases(
    root: Path,
    model_alias: str,
    stage: str,
) -> Tuple[List[str], Dict[str, int]]:
    all_cases = valid_case_dirs(root)

    stats = {
        "all": len(all_cases),
        "screen_yes": 0,
        "screen_no": 0,
        "screen_unclear": 0,
        "screen_missing": 0,
    }

    if stage == "screen":
        return all_cases, stats

    selected: List[str] = []

    for case_id in all_cases:
        label = read_screen_label(root / case_id, model_alias)

        if label == "Yes":
            stats["screen_yes"] += 1
            selected.append(case_id)
        elif label == "Unclear":
            stats["screen_unclear"] += 1
            selected.append(case_id)
        elif label == "No":
            stats["screen_no"] += 1
        else:
            stats["screen_missing"] += 1

    return selected, stats


def build_packages(
    root: Path,
    case_ids: Sequence[str],
    cfg,
    include_patches: bool,
) -> List[CasePackage]:
    packages: List[CasePackage] = []

    for case_id in case_ids:
        pkg = build_case_package(
            root / case_id,
            cfg,
            include_patches=include_patches,
        )
        if pkg is not None:
            packages.append(pkg)

    return packages


def batch_prompt(batch: Sequence[CasePackage]) -> str:
    return (
        "Analyze every CASE independently.\n\n"
        + "\n\n".join(pkg.text for pkg in batch)
    )


def dry_run(
    stage: str,
    packages: Sequence[CasePackage],
    batches: Sequence[Sequence[CasePackage]],
    system_prompt: str,
    log_file: Path,
) -> None:
    total_chars = sum(pkg.chars for pkg in packages)
    avg = total_chars // max(len(packages), 1)

    log("=" * 72, log_file)
    log("DRY RUN: no API request will be made", log_file)
    log(f"Stage          : {stage}", log_file)
    log(f"Cases packaged : {len(packages)}", log_file)
    log(f"Cases w/ patch : {sum(pkg.has_patch for pkg in packages)}", log_file)
    log(f"Evidence chars : {total_chars}", log_file)
    log(f"Average/case   : {avg}", log_file)
    log(f"API batches    : {len(batches)}", log_file)

    if batches:
        log(
            f"Avg batch size : "
            f"{sum(len(batch) for batch in batches) / len(batches):.2f}",
            log_file,
        )
        avg_batch_chars = (
            sum(sum(pkg.chars for pkg in batch) for batch in batches)
            // len(batches)
        )
        log(f"Avg batch chars: {avg_batch_chars}", log_file)

    log("Largest packaged cases:", log_file)

    for pkg in sorted(packages, key=lambda x: x.chars, reverse=True)[:10]:
        log(
            f"  {pkg.case_id}: packaged={pkg.chars} "
            f"original_java={pkg.original_java_chars} "
            f"original_patch={pkg.original_patch_chars} "
            f"has_patch={pkg.has_patch}",
            log_file,
        )

    rough_tokens = (
        total_chars + len(batches) * len(system_prompt)
    ) // 4

    log(
        f"Very rough prompt-token estimate: ~{rough_tokens:,} tokens",
        log_file,
    )
    log("=" * 72, log_file)


class StageRunner:
    def __init__(
        self,
        *,
        root: Path,
        stage: str,
        model_alias: str,
        client: LLMClient,
        max_output_tokens: int,
        log_file: Path,
    ):
        self.root = root
        self.stage = stage
        self.client = client
        self.max_output_tokens = max_output_tokens
        self.log_file = log_file

        self.usage = UsageTracker()

        self.system_prompt = (
            SCREEN_PROMPT
            if stage == "screen"
            else ANALYZE_PROMPT
        )

        self.validator = (
            validate_screen
            if stage == "screen"
            else validate_analyze
        )

        self.out_name = result_filename(model_alias, stage)

    def _call(
        self,
        batch: Sequence[CasePackage],
    ) -> CallOutcome:
        user_prompt = batch_prompt(batch)
        ids = [pkg.case_id for pkg in batch]

        try:
            response = self.client.complete(
                system_prompt=self.system_prompt,
                user_prompt=user_prompt,
                max_tokens=self.max_output_tokens,
            )
        except Exception as exc:
            log(
                f"[API FAIL] cases={','.join(ids)} error={exc}",
                self.log_file,
            )
            return CallOutcome(None)

        if response is None:
            log(
                f"[API FAIL] cases={','.join(ids)} empty response object",
                self.log_file,
            )
            return CallOutcome(None)

        raw = response.text or ""
        usage = response.usage or {}

        self.usage.add(
            usage,
            len(self.system_prompt) + len(user_prompt),
        )

        cache_hit = usage.get(
            "prompt_cache_hit_tokens",
            usage.get("cached_tokens", "NA"),
        )

        completion_tokens = usage.get("completion_tokens")
        likely_truncated = (
            response.finish_reason == "length"
            or (
                isinstance(completion_tokens, (int, float))
                and completion_tokens >= self.max_output_tokens
            )
        )

        log(
            f"[usage] cases={','.join(ids)} "
            f"prompt={usage.get('prompt_tokens', 'NA')} "
            f"cache_hit={cache_hit} "
            f"completion={completion_tokens if completion_tokens is not None else 'NA'} "
            f"total={usage.get('total_tokens', 'NA')} "
            f"output_limit_hit={'yes' if likely_truncated else 'no'}",
            self.log_file,
        )

        return CallOutcome(
            results=extract_results(raw),
            raw=raw,
            likely_truncated=likely_truncated,
        )

    def _failure_dir(self) -> Path:
        path = self.root / ".llm_failed" / self.stage
        path.mkdir(parents=True, exist_ok=True)
        return path

    def _save_failed_raw(
        self,
        case_ids: Sequence[str],
        *,
        raw: str,
        reason: str,
        scope: str,
        likely_truncated: bool = False,
    ) -> None:
        """
        Persist the provider's raw text whenever parsing/validation fails.

        One file is written per affected case so failures can be inspected by
        case ID even when the original request contained multiple cases.
        """
        if not raw:
            return

        header = (
            f"stage: {self.stage}\n"
            f"scope: {scope}\n"
            f"reason: {reason}\n"
            f"likely_output_truncated: {likely_truncated}\n"
            f"max_output_tokens: {self.max_output_tokens}\n"
            f"cases_in_response: {','.join(case_ids)}\n"
            "\n===== RAW MODEL RESPONSE =====\n"
        )

        failure_dir = self._failure_dir()

        with WRITE_LOCK:
            for case_id in case_ids:
                path = failure_dir / f"{case_id}__{scope}.txt"
                path.write_text(
                    header + raw,
                    encoding="utf-8",
                )

    def _clear_failed_raw(self, case_id: str) -> None:
        failure_dir = self.root / ".llm_failed" / self.stage
        if not failure_dir.exists():
            return

        with WRITE_LOCK:
            for path in failure_dir.glob(f"{case_id}__*.txt"):
                try:
                    path.unlink()
                except OSError:
                    pass

    def _save(
        self,
        case_id: str,
        obj: Dict[str, Any],
    ) -> None:
        path = self.root / case_id / self.out_name

        with WRITE_LOCK:
            path.write_text(
                json.dumps(
                    obj,
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )

        self._clear_failed_raw(case_id)

        if self.stage == "screen":
            log(
                f"[OK] {case_id}: "
                f"Type_Related={obj['Type_Related']}",
                self.log_file,
            )
        else:
            log(
                f"[OK] {case_id}: "
                f"Type={obj['Type_Related']} "
                f"Tier={obj['Compiler_Tier']} "
                f"Component={obj.get('Faulty_Compiler_Component', 'Unknown')} "
                f"PrimaryFault={obj['Primary_Faulty_Optimization']} "
                f"Evidence={obj.get('Optimization_Evidence_Basis', 'Unknown')} "
                f"Confidence={obj.get('Optimization_Confidence', 'Low')} "
                f"UnknownReason={obj.get('Optimization_Unknown_Reason', 'N/A')} "
                f"Interacting={obj['Interacting_Optimizations']}",
                self.log_file,
            )

    def _record_failure(
        self,
        case_ids: Sequence[str],
        outcome: CallOutcome,
        *,
        reason: str,
        scope: str,
    ) -> None:
        self._save_failed_raw(
            case_ids,
            raw=outcome.raw,
            reason=reason,
            scope=scope,
            likely_truncated=outcome.likely_truncated,
        )
        suffix = (
            f"; raw saved to .llm_failed/{self.stage}/"
            if outcome.raw
            else ""
        )
        log(f"[{scope}] cases={','.join(case_ids)}: {reason}{suffix}", self.log_file)

    def _process_response(
        self,
        packages: Sequence[CasePackage],
        outcome: CallOutcome,
        *,
        scope: str,
    ) -> List[CasePackage]:
        """Validate and save one response, returning cases that need retry."""
        case_ids = [pkg.case_id for pkg in packages]
        if not outcome.results:
            reason = "API/no response" if not outcome.raw else "invalid JSON response"
            if outcome.likely_truncated:
                reason += " (likely output-token truncation)"
            self._record_failure(
                case_ids,
                outcome,
                reason=reason,
                scope=f"{scope}_parse",
            )
            return list(packages)

        by_id = {
            str(obj.get("ID", "")).strip(): obj
            for obj in outcome.results
        }
        failed: List[CasePackage] = []

        for pkg in packages:
            obj = by_id.get(pkg.case_id)
            if obj is None:
                reason = "valid JSON but expected case ID missing"
                failure_scope = f"{scope}_missing_id"
            else:
                ok, validation_reason = self.validator(obj, pkg.case_id)
                if ok:
                    self._save(pkg.case_id, obj)
                    continue
                reason = f"schema validation failed: {validation_reason}"
                failure_scope = f"{scope}_schema"

            self._record_failure(
                [pkg.case_id],
                outcome,
                reason=reason,
                scope=failure_scope,
            )
            failed.append(pkg)

        return failed

    def _single_fallback(self, pkg: CasePackage) -> None:
        log(f"[fallback-single] {pkg.case_id}", self.log_file)
        self._process_response([pkg], self._call([pkg]), scope="single")

    def process_batch(
        self,
        batch: Sequence[CasePackage],
    ) -> None:
        # Existing result file is the resume state.
        pending = [
            pkg
            for pkg in batch
            if not (
                self.root
                / pkg.case_id
                / self.out_name
            ).exists()
        ]

        if not pending:
            return

        pending_ids = [pkg.case_id for pkg in pending]

        log(
            f"[batch] size={len(pending)} "
            f"evidence_chars={sum(pkg.chars for pkg in pending)} "
            f"cases={','.join(pending_ids)}",
            self.log_file,
        )

        failed = self._process_response(
            pending,
            self._call(pending),
            scope="batch",
        )
        for pkg in failed:
            self._single_fallback(pkg)

    def run(
        self,
        batches: Sequence[Sequence[CasePackage]],
        workers: int,
    ) -> None:
        if workers <= 1:
            for batch in batches:
                self.process_batch(batch)
        else:
            with ThreadPoolExecutor(
                max_workers=workers
            ) as pool:
                futures = [
                    pool.submit(
                        self.process_batch,
                        batch,
                    )
                    for batch in batches
                ]

                for future in as_completed(futures):
                    try:
                        future.result()
                    except Exception as exc:
                        log(
                            f"[worker-error] {exc}",
                            self.log_file,
                        )

        totals = self.usage.snapshot()

        log(
            "[usage-summary] "
            + " ".join(
                f"{key}={value}"
                for key, value in totals.items()
            ),
            self.log_file,
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Two-stage HotSpot JIT type and optimization analyzer"
    )
    parser.add_argument("--dir", required=True)
    parser.add_argument("--stage", choices=("screen", "analyze"), default="screen")
    parser.add_argument("--platform", default="deepseek")
    parser.add_argument("--model", default="deepseek")
    parser.add_argument("--model-id")
    parser.add_argument("--base-url")
    parser.add_argument("--api-key-env")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--clear", action="store_true")
    parser.add_argument("--no-patches", action="store_true")
    parser.add_argument(
        "--limit", type=int, default=0, help="Process only first N selected cases."
    )
    parser.add_argument(
        "--batch-size", type=int, default=0,
        help="0 = stage default (screen 5, analyze 2)."
    )
    parser.add_argument("--max-batch-chars", type=int, default=80000)
    parser.add_argument(
        "--max-output-tokens", type=int, default=0, help="0 = stage default."
    )
    args = parser.parse_args()

    root = Path(args.dir)
    if not root.exists():
        raise SystemExit(f"Directory not found: {root}")

    out_name = result_filename(args.model, args.stage)
    log_file = root / f"run_llm_{safe_name(args.model)}_{args.stage}.log"
    log_file.write_text("", encoding="utf-8")

    selected, stats = select_cases(root, args.model, args.stage)

    if args.limit > 0:
        selected = selected[: args.limit]

    if args.clear and not args.dry_run:
        removed = 0

        for case_id in selected:
            path = root / case_id / out_name

            if path.exists():
                path.unlink()
                removed += 1

        log(f"[clear] removed {removed} {out_name} files", log_file)

    remaining = [
        case_id
        for case_id in selected
        if not (
            root
            / case_id
            / out_name
        ).exists()
    ]

    cfg = SCREEN_CFG if args.stage == "screen" else ANALYZE_CFG
    batch_size = args.batch_size or (5 if args.stage == "screen" else 2)
    max_output_tokens = args.max_output_tokens or (
        2500 if args.stage == "screen" else 6000
    )

    log("=" * 72, log_file)
    log(f"Stage             : {args.stage}", log_file)
    log(f"Root              : {root}", log_file)
    log(f"Model alias       : {args.model}", log_file)
    log(f"Model ID          : {args.model_id}", log_file)
    log(f"All valid cases   : {stats['all']}", log_file)

    if args.stage == "analyze":
        log(
            f"Screen labels     : "
            f"Yes={stats['screen_yes']} "
            f"Unclear={stats['screen_unclear']} "
            f"No={stats['screen_no']} "
            f"Missing={stats['screen_missing']}",
            log_file,
        )

    log(f"Selected          : {len(selected)}", log_file)
    log(f"Already completed : {len(selected) - len(remaining)}", log_file)
    log(f"Remaining         : {len(remaining)}", log_file)
    log(f"Output            : {out_name}", log_file)
    log(
        f"Batch policy      : size<={batch_size}, "
        f"evidence_chars<={args.max_batch_chars}",
        log_file,
    )
    log(f"Max output tokens : {max_output_tokens}", log_file)
    log(f"Failure raw dir   : {root / '.llm_failed' / args.stage}", log_file)
    log("=" * 72, log_file)

    packages = build_packages(
        root,
        remaining,
        cfg,
        include_patches=not args.no_patches,
    )

    batches = make_batches(packages, batch_size, args.max_batch_chars)

    if args.dry_run:
        dry_run(
            args.stage,
            packages,
            batches,
            SCREEN_PROMPT if args.stage == "screen" else ANALYZE_PROMPT,
            log_file,
        )
        return

    client = LLMClient(
        platform=args.platform,
        model_alias=args.model,
        model_id=args.model_id,
        base_url=args.base_url,
        api_key_env=args.api_key_env,
    )

    runner = StageRunner(
        root=root,
        stage=args.stage,
        model_alias=args.model,
        client=client,
        max_output_tokens=max_output_tokens,
        log_file=log_file,
    )

    runner.run(batches, max(args.workers, 1))


if __name__ == "__main__":
    main()
