#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Export complete bug-case directories selected by an LLM type label."""

from __future__ import annotations

import argparse
import json
import shutil
from collections import Counter
from pathlib import Path
from typing import Any, Optional

from tools.llm_common import read_json_object, result_filename


LABEL_MAP = {
    "related": "Yes",
    "unrelated": "No",
    "unclear": "Unclear",
    "yes": "Yes",
    "no": "No",
}


def normalize_label(value: Any) -> Optional[str]:
    value = str(value or "").strip()
    return value if value in {"Yes", "No", "Unclear"} else None


def same_or_nested(path: Path, parent: Path) -> bool:
    """Return True if path == parent or path is under parent."""
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def copy_case(src: Path, dst: Path, overwrite: bool) -> str:
    """
    Copy one complete case directory.

    Returns one of: copied, overwritten, skipped_existing.
    """
    if dst.exists():
        if not overwrite:
            return "skipped_existing"
        if dst.is_dir() and not dst.is_symlink():
            shutil.rmtree(dst)
        else:
            dst.unlink()
        action = "overwritten"
    else:
        action = "copied"

    # copytree recursively preserves every file/subdirectory. copy2 is the
    # default file copier and preserves file metadata where the OS permits it.
    # symlinks=True preserves symbolic links instead of dereferencing them.
    shutil.copytree(src, dst, copy_function=shutil.copy2, symlinks=True)
    return action


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Copy bug-case directories selected by the LLM type label."
    )
    parser.add_argument(
        "--dir", required=True,
        help="Root directory whose immediate subdirectories are bug cases."
    )
    parser.add_argument(
        "--output", required=True,
        help="Destination directory for selected complete case directories."
    )
    parser.add_argument(
        "--label", required=True,
        choices=["related", "unrelated", "unclear", "yes", "no"],
        help="Label to export: related=Yes, unrelated=No, unclear=Unclear."
    )
    parser.add_argument(
        "--stage", choices=["screen", "analyze"], default="analyze",
        help=(
            "analyze reads Stage 2; screen reads the high-recall Stage 1 result."
        )
    )
    parser.add_argument(
        "--model", default="deepseek",
        help="Model alias used in llm_<model>_*.json."
    )
    parser.add_argument(
        "--result-file",
        help=(
            "Override the default llm_<model>_type/screen.json filename."
        )
    )
    parser.add_argument(
        "--overwrite", action="store_true",
        help="Replace destination case directories that already exist."
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Print selections without copying."
    )
    args = parser.parse_args()

    root = Path(args.dir).expanduser().resolve()
    output = Path(args.output).expanduser().resolve()

    if not root.is_dir():
        raise SystemExit(f"Source root does not exist or is not a directory: {root}")

    if output == root:
        raise SystemExit("--output must not be the same directory as --dir")

    wanted_label = LABEL_MAP[args.label]
    result_file = (
        args.result_file
        if args.result_file
        else result_filename(args.model, args.stage)
    )

    # Snapshot the top-level entries before creating output. This also prevents
    # an output directory inside root from being discovered midway through the
    # run and treated as a bug case.
    case_dirs = [
        path
        for path in sorted(root.iterdir(), key=lambda p: p.name)
        if path.is_dir() and not path.name.startswith(".")
    ]

    stats = Counter()
    selected = []

    for case_dir in case_dirs:
        # If --output is inside --dir, do not inspect the destination itself or
        # anything inside it as input cases.
        if same_or_nested(case_dir, output):
            continue

        stats["case_dirs_seen"] += 1
        result_path = case_dir / result_file

        if not result_path.is_file():
            stats["missing_result"] += 1
            continue

        obj = read_json_object(result_path)
        if obj is None:
            stats["invalid_json"] += 1
            print(f"[WARN] invalid JSON: {result_path}")
            continue

        label = normalize_label(obj.get("Type_Related"))
        if label is None:
            stats["invalid_label"] += 1
            print(f"[WARN] invalid/missing Type_Related: {result_path}")
            continue

        stats[f"label_{label}"] += 1
        if label == wanted_label:
            selected.append(case_dir)

    print("=" * 78)
    print("Export LLM-labeled bug cases")
    print("=" * 78)
    print(f"Source root       : {root}")
    print(f"Output            : {output}")
    print(f"Stage             : {args.stage}")
    print(f"Result file       : {result_file}")
    print(f"Requested label   : {wanted_label} ({args.label})")
    print(f"Case dirs seen    : {stats['case_dirs_seen']}")
    print(
        "Labels found      : "
        f"Yes={stats['label_Yes']} "
        f"No={stats['label_No']} "
        f"Unclear={stats['label_Unclear']}"
    )
    print(f"Missing result    : {stats['missing_result']}")
    print(f"Invalid JSON      : {stats['invalid_json']}")
    print(f"Invalid label     : {stats['invalid_label']}")
    print(f"Selected          : {len(selected)}")

    if args.dry_run:
        print("\nDRY RUN: no files will be copied")
        for case_dir in selected:
            print(f"[SELECT] {case_dir.name}")
        print("=" * 78)
        return

    output.mkdir(parents=True, exist_ok=True)

    for index, case_dir in enumerate(selected, 1):
        dst = output / case_dir.name
        action = copy_case(case_dir, dst, args.overwrite)
        stats[action] += 1
        print(f"[{index:>4}/{len(selected)}] [{action}] {case_dir.name}")

    manifest = {
        "source_root": str(root),
        "output": str(output),
        "stage": args.stage,
        "result_file": result_file,
        "requested_label": wanted_label,
        "selected_count": len(selected),
        "selected_case_ids": [case_dir.name for case_dir in selected],
        "copy_stats": {
            "copied": stats["copied"],
            "overwritten": stats["overwritten"],
            "skipped_existing": stats["skipped_existing"],
        },
        "scan_stats": {
            "case_dirs_seen": stats["case_dirs_seen"],
            "yes": stats["label_Yes"],
            "no": stats["label_No"],
            "unclear": stats["label_Unclear"],
            "missing_result": stats["missing_result"],
            "invalid_json": stats["invalid_json"],
            "invalid_label": stats["invalid_label"],
        },
    }
    (output / "export_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print("\nCopy summary:")
    print(f"  copied           : {stats['copied']}")
    print(f"  overwritten      : {stats['overwritten']}")
    print(f"  skipped existing : {stats['skipped_existing']}")
    print(f"  manifest         : {output / 'export_manifest.json'}")
    print("=" * 78)


if __name__ == "__main__":
    main()
