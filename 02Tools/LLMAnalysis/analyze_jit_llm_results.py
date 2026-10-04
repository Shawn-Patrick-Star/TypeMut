#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
TypeFuzz HotSpot JIT LLM result statistics

Optimization names are normalized with the same closed HotSpot vocabulary used
by the analysis pipeline. Statistics default to Stage-2 Type_Related == "Yes"
and also report Stage-1 -> Stage-2 label transitions.

Expected per-case files:
    llm_<model>_screen.json
    llm_<model>_type.json

Example:
    python analyze_jit_llm_results.py \
        --dir ../new_crawl/Hotspot_filtered \
        --model deepseek
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from itertools import zip_longest
from pathlib import Path
from typing import Any, Dict, Iterable, Sequence

from tools.hotspot_optimization_taxonomy import (
    HOTSPOT_OPTIMIZATION_CATEGORY_LABELS,
    HOTSPOT_OPTIMIZATION_TAXONOMY,
    infer_optimization_from_evidence,
    normalize_optimization_name,
)
from tools.llm_common import read_json_object, result_filename, safe_name


CATEGORY_ORDER = list(HOTSPOT_OPTIMIZATION_CATEGORY_LABELS.values())

CATEGORY_PASSES = {
    HOTSPOT_OPTIMIZATION_CATEGORY_LABELS[key]: list(passes)
    for key, passes in HOTSPOT_OPTIMIZATION_TAXONOMY.items()
}

PASS_TO_CATEGORY = {
    p: category
    for category, passes in CATEGORY_PASSES.items()
    for p in passes
}

def clean(v: Any) -> str:
    return " ".join(str(v or "").split())


def normalize_label(v: Any) -> str:
    x = clean(v)
    return x if x in {"Yes", "No", "Unclear"} else "Missing"


def normalize_tier(v: Any) -> str:
    x = clean(v).lower()
    if x in {"c1", "client compiler", "c1 / client compiler"}:
        return "C1"
    if x in {"c2", "server compiler", "c2 / server compiler"}:
        return "C2"
    if x == "shared":
        return "Shared"
    return "Unknown"


def normalize_pass(v: Any) -> str:
    return normalize_optimization_name(clean(v))


def write_csv(path: Path, fields: Sequence[str], rows: Iterable[Dict[str, Any]]):
    with path.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for row in rows:
            w.writerow(row)


def pct(count: int, total: int) -> float:
    return round(100.0 * count / total, 3) if total else 0.0


def latex_escape(text: str) -> str:
    replacements = {
        "\\": r"\textbackslash{}",
        "&": r"\&",
        "%": r"\%",
        "$": r"\$",
        "#": r"\#",
        "_": r"\_",
        "{": r"\{",
        "}": r"\}",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    return text


def render_taxonomy_console(category_counts, involved_counts, total_n: int) -> str:
    """
    Render the official HotSpot taxonomy as a readable console table.

    Category count:
        unique cases involving >=1 pass in this category

    Sub-pass count:
        unique cases in which the pass appears as Primary OR Interacting
    """
    rows = []
    width = 48

    for category in CATEGORY_ORDER:
        c = category_counts.get(category, 0)
        rows.append(
            f"{category:<{width}} {c:>5} ({pct(c, total_n):6.2f}%)"
        )
        for p in CATEGORY_PASSES[category]:
            pc = involved_counts.get(p, 0)
            rows.append(
                f"  - {p:<{width-4}} {pc:>5} ({pct(pc, total_n):6.2f}%)"
            )
        rows.append("")

    return "\n".join(rows).rstrip()


def make_taxonomy_latex(category_counts, involved_counts) -> str:
    """
    Generate a two-column LaTeX table for the official HotSpot taxonomy.

    IMPORTANT:
    - category totals count UNIQUE cases involving >=1 pass in category
    - sub-pass counts are based on Primary OR Interacting involvement
    - therefore sub-pass counts are non-exclusive and need not sum to category total
    """
    blocks = {}

    for category in CATEGORY_ORDER:
        rows = [
            (
                rf"\textbf{{{latex_escape(category)}}}",
                rf"\textbf{{{category_counts.get(category, 0)}}}",
            )
        ]

        for pass_name in CATEGORY_PASSES[category]:
            rows.append(
                (
                    rf"\quad -- {latex_escape(pass_name)}",
                    str(involved_counts.get(pass_name, 0)),
                )
            )
        blocks[category] = rows

    split_at = (len(CATEGORY_ORDER) + 1) // 2
    pairs = list(zip_longest(
        CATEGORY_ORDER[:split_at],
        CATEGORY_ORDER[split_at:],
    ))

    body = []

    for pair_idx, (left_cat, right_cat) in enumerate(pairs):
        left = blocks[left_cat]
        right = blocks[right_cat] if right_cat is not None else []
        max_len = max(len(left), len(right))

        for i in range(max_len):
            l_name, l_count = left[i] if i < len(left) else ("", "")
            r_name, r_count = right[i] if i < len(right) else ("", "")
            body.append(
                f"    {l_name} & {l_count} & {r_name} & {r_count} \\\\"
            )

        if pair_idx != len(pairs) - 1:
            body.append(r"    \cmidrule(lr){1-2} \cmidrule(lr){3-4}")

    return "\n".join(
        [
            r"\begin{table*}[t]",
            r"  \centering",
            r"  \caption{HotSpot optimization taxonomy and involved bug counts.}",
            r"  \label{tab:jit_pipeline_taxonomy_side}",
            r"  \scriptsize",
            r"  \renewcommand{\arraystretch}{0.85}",
            r"  \begin{tabular}{l r @{\hspace{2em}} l r}",
            r"    \toprule",
            r"    \textbf{Optimization Category} & \textbf{Count} & \textbf{Optimization Category} & \textbf{Count} \\",
            r"    \midrule",
            *body,
            r"    \bottomrule",
            r"  \end{tabular}",
            r"\end{table*}",
            "",
            r"% Counting note:",
            r"% Category total = unique cases involving >=1 pass in category.",
            r"% Sub-pass count = unique cases where pass is Primary or Interacting.",
            r"% Sub-pass counts are non-exclusive and need not sum to category total.",
            r"% Optimization names use the official HotSpot closed vocabulary.",
            r"% Unknown optimization results are excluded from category counts.",
        ]
    )

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--model", default="deepseek")
    ap.add_argument("--screen-file", default=None)
    ap.add_argument("--result-file", default=None)
    ap.add_argument("--output", default=None)
    ap.add_argument(
        "--labels",
        nargs="+",
        choices=["Yes", "No", "Unclear"],
        default=["Yes"],
        help="Stage-2 labels included in optimization statistics (default: Yes)",
    )
    args = ap.parse_args()

    root = Path(args.dir).resolve()
    if not root.is_dir():
        raise SystemExit(f"Directory not found: {root}")

    model = safe_name(args.model)
    screen_name = args.screen_file or result_filename(model, "screen")
    analyze_name = args.result_file or result_filename(model, "analyze")
    out = Path(args.output).resolve() if args.output else root / "analysis_jit_llm"
    out.mkdir(parents=True, exist_ok=True)

    records = []
    malformed = []

    for case_dir in sorted(root.iterdir()):
        if not case_dir.is_dir() or case_dir.name.startswith("."):
            continue

        analyze_path = case_dir / analyze_name
        if not analyze_path.exists():
            continue

        a = read_json_object(analyze_path)
        if not a:
            malformed.append((case_dir.name, "invalid Stage-2 JSON"))
            continue

        analyze_label = normalize_label(a.get("Type_Related"))
        if analyze_label == "Missing":
            malformed.append((case_dir.name, "invalid Stage-2 Type_Related"))
            continue

        s_path = case_dir / screen_name
        s = read_json_object(s_path) if s_path.exists() else None
        screen_label = normalize_label(s.get("Type_Related")) if s else "Missing"

        original_primary = normalize_pass(
            a.get("Primary_Faulty_Optimization",
                  a.get("Primary_Optimization", "Unknown"))
        )
        original_unknown_reason = (
            clean(a.get("Optimization_Unknown_Reason", "N/A")) or "N/A"
        )

        primary = original_primary
        optimization_postprocessed = False
        if (
            primary == "Unknown"
            and original_unknown_reason == "NoTaxonomyMatch"
        ):
            inferred = infer_optimization_from_evidence(
                a.get("Faulty_Compiler_Component", ""),
                a.get("Optimization_Mapping_Reason", ""),
                a.get("Root_Cause_In_Optimization", ""),
                a.get("Root_Cause_Summary", ""),
            )
            if inferred != "Unknown":
                primary = inferred
                optimization_postprocessed = True

        effective_unknown_reason = (
            "N/A" if primary != "Unknown" else original_unknown_reason
        )

        raw_interacting = a.get(
            "Interacting_Optimizations",
            a.get("Secondary_Optimizations", []),
        )
        if not isinstance(raw_interacting, list):
            raw_interacting = []

        interacting = []
        seen = set()
        for item in raw_interacting:
            name = normalize_pass(item)
            if name == "Unknown" or name == primary or name in seen:
                continue
            seen.add(name)
            interacting.append(name)

        involved = []
        seen2 = set()
        for name in [primary, *interacting]:
            if name == "Unknown" or name in seen2:
                continue
            seen2.add(name)
            involved.append(name)

        records.append({
            "ID": str(a.get("ID") or case_dir.name),
            "Screen": screen_label,
            "Analyze": analyze_label,
            "Tier": normalize_tier(a.get("Compiler_Tier")),
            "Faulty_Compiler_Component": str(a.get("Faulty_Compiler_Component", "")),
            "Original_Primary": original_primary,
            "Primary": primary,
            "Optimization_Postprocessed": optimization_postprocessed,
            "Original_Optimization_Unknown_Reason": original_unknown_reason,
            "Interacting": interacting,
            "Involved": involved,
            "Optimization_Mapping_Reason": str(a.get("Optimization_Mapping_Reason", "")),
            "Optimization_Evidence_Basis": clean(a.get("Optimization_Evidence_Basis", "Unknown")) or "Unknown",
            "Optimization_Confidence": clean(a.get("Optimization_Confidence", "Low")) or "Low",
            "Optimization_Unknown_Reason": effective_unknown_reason,
            "Type_Mechanism": str(a.get("Type_Mechanism", "")),
            "Root_Cause_In_Optimization": str(a.get("Root_Cause_In_Optimization", "")),
            "Root_Cause_Summary": str(a.get("Root_Cause_Summary", "")),
            "Uncertainty": str(a.get("Uncertainty", "")),
        })

    selected = [r for r in records if r["Analyze"] in set(args.labels)]
    n = len(selected)

    screen_counts = Counter(r["Screen"] for r in records)
    analyze_counts = Counter(r["Analyze"] for r in records)
    transitions = Counter((r["Screen"], r["Analyze"]) for r in records)

    tier_counts = Counter(r["Tier"] for r in selected)
    primary_counts = Counter(r["Primary"] for r in selected)
    postprocessed = [r for r in selected if r["Optimization_Postprocessed"]]
    postprocessed_counts = Counter(r["Primary"] for r in postprocessed)
    optimization_evidence_counts = Counter(
        r["Optimization_Evidence_Basis"] for r in selected
    )
    optimization_confidence_counts = Counter(
        r["Optimization_Confidence"] for r in selected
    )
    unknown_reason_counts = Counter(
        r["Optimization_Unknown_Reason"]
        for r in selected
        if r["Primary"] == "Unknown"
    )
    interacting_counts = Counter()
    involved_counts = Counter()

    primary_category_counts = Counter()
    category_case_ids = defaultdict(set)

    for r in selected:
        interacting_counts.update(r["Interacting"])
        involved_counts.update(r["Involved"])

        pc = PASS_TO_CATEGORY.get(r["Primary"])
        if pc:
            primary_category_counts[pc] += 1

        for p in r["Involved"]:
            cat = PASS_TO_CATEGORY.get(p)
            if cat:
                category_case_ids[cat].add(r["ID"])

    category_counts = Counter(
        {cat: len(ids) for cat, ids in category_case_ids.items()}
    )
    for cat in CATEGORY_ORDER:
        category_counts.setdefault(cat, 0)
        primary_category_counts.setdefault(cat, 0)

    # 1) Stage transition analysis
    write_csv(
        out / "screen_to_analyze_transition.csv",
        ["screen_label", "analyze_label", "case_count", "percent_of_stage2_cases"],
        ({
            "screen_label": s,
            "analyze_label": a,
            "case_count": c,
            "percent_of_stage2_cases": pct(c, len(records)),
        } for (s, a), c in transitions.most_common())
    )

    # 2) Selected case manifest
    write_csv(
        out / "selected_cases.csv",
        [
            "ID", "Screen_Type_Related", "Analyze_Type_Related",
            "Compiler_Tier", "Faulty_Compiler_Component",
            "Original_Primary_Faulty_Optimization",
            "Primary_Faulty_Optimization", "Optimization_Postprocessed",
            "Interacting_Optimizations", "Involved_Optimizations",
            "Optimization_Mapping_Reason", "Optimization_Evidence_Basis",
            "Optimization_Confidence",
            "Original_Optimization_Unknown_Reason",
            "Optimization_Unknown_Reason",
            "Type_Mechanism", "Root_Cause_In_Optimization",
            "Root_Cause_Summary", "Uncertainty"
        ],
        ({
            "ID": r["ID"],
            "Screen_Type_Related": r["Screen"],
            "Analyze_Type_Related": r["Analyze"],
            "Compiler_Tier": r["Tier"],
            "Faulty_Compiler_Component": r["Faulty_Compiler_Component"],
            "Original_Primary_Faulty_Optimization": r["Original_Primary"],
            "Primary_Faulty_Optimization": r["Primary"],
            "Optimization_Postprocessed": r["Optimization_Postprocessed"],
            "Interacting_Optimizations": "; ".join(r["Interacting"]),
            "Involved_Optimizations": "; ".join(r["Involved"]),
            "Optimization_Mapping_Reason": r["Optimization_Mapping_Reason"],
            "Optimization_Evidence_Basis": r["Optimization_Evidence_Basis"],
            "Optimization_Confidence": r["Optimization_Confidence"],
            "Original_Optimization_Unknown_Reason": r["Original_Optimization_Unknown_Reason"],
            "Optimization_Unknown_Reason": r["Optimization_Unknown_Reason"],
            "Type_Mechanism": r["Type_Mechanism"],
            "Root_Cause_In_Optimization": r["Root_Cause_In_Optimization"],
            "Root_Cause_Summary": r["Root_Cause_Summary"],
            "Uncertainty": r["Uncertainty"],
        } for r in selected)
    )

    # 3) Tier
    write_csv(
        out / "compiler_tier_distribution.csv",
        ["tier", "count", "percent_of_cases"],
        ({
            "tier": k, "count": v, "percent_of_cases": pct(v, n)
        } for k, v in tier_counts.most_common())
    )

    # 4) Official HotSpot pass distributions
    def pass_rows(counter):
        for k, v in counter.most_common():
            yield {
                "optimization": k,
                "count": v,
                "percent_of_cases": pct(v, n),
            }

    write_csv(
        out / "primary_faulty_optimization_distribution.csv",
        ["optimization", "count", "percent_of_cases"],
        pass_rows(primary_counts)
    )
    write_csv(
        out / "interacting_optimization_distribution.csv",
        ["optimization", "count", "percent_of_cases"],
        pass_rows(interacting_counts)
    )
    write_csv(
        out / "involved_optimization_distribution.csv",
        ["optimization", "count", "percent_of_cases"],
        pass_rows(involved_counts)
    )

    # 5) Deterministic component->taxonomy remapping audit
    write_csv(
        out / "component_postprocessed_cases.csv",
        [
            "ID", "Faulty_Compiler_Component",
            "Original_Primary_Faulty_Optimization",
            "Mapped_Primary_Faulty_Optimization",
            "Original_Optimization_Unknown_Reason",
            "Optimization_Mapping_Reason",
            "Optimization_Evidence_Basis",
            "Optimization_Confidence",
            "Root_Cause_In_Optimization",
            "Root_Cause_Summary",
        ],
        ({
            "ID": r["ID"],
            "Faulty_Compiler_Component": r["Faulty_Compiler_Component"],
            "Original_Primary_Faulty_Optimization": r["Original_Primary"],
            "Mapped_Primary_Faulty_Optimization": r["Primary"],
            "Original_Optimization_Unknown_Reason": r["Original_Optimization_Unknown_Reason"],
            "Optimization_Mapping_Reason": r["Optimization_Mapping_Reason"],
            "Optimization_Evidence_Basis": r["Optimization_Evidence_Basis"],
            "Optimization_Confidence": r["Optimization_Confidence"],
            "Root_Cause_In_Optimization": r["Root_Cause_In_Optimization"],
            "Root_Cause_Summary": r["Root_Cause_Summary"],
        } for r in postprocessed)
    )
    write_csv(
        out / "component_postprocessed_distribution.csv",
        ["optimization", "count", "percent_of_postprocessed_cases"],
        ({
            "optimization": k,
            "count": v,
            "percent_of_postprocessed_cases": pct(v, len(postprocessed)),
        } for k, v in postprocessed_counts.most_common())
    )

    # 6) Optimization evidence quality
    write_csv(
        out / "optimization_evidence_basis_distribution.csv",
        ["evidence_basis", "count", "percent_of_cases"],
        ({
            "evidence_basis": k,
            "count": v,
            "percent_of_cases": pct(v, n),
        } for k, v in optimization_evidence_counts.most_common())
    )
    write_csv(
        out / "optimization_confidence_distribution.csv",
        ["confidence", "count", "percent_of_cases"],
        ({
            "confidence": k,
            "count": v,
            "percent_of_cases": pct(v, n),
        } for k, v in optimization_confidence_counts.most_common())
    )
    write_csv(
        out / "unknown_optimization_reason_distribution.csv",
        ["unknown_reason", "count", "percent_of_unknown_primary"],
        ({
            "unknown_reason": k,
            "count": v,
            "percent_of_unknown_primary": pct(v, sum(unknown_reason_counts.values())),
        } for k, v in unknown_reason_counts.most_common())
    )

    # 7) Official HotSpot taxonomy
    write_csv(
        out / "category_case_distribution.csv",
        ["category", "count", "percent_of_cases"],
        ({
            "category": cat,
            "count": category_counts[cat],
            "percent_of_cases": pct(category_counts[cat], n),
        } for cat in CATEGORY_ORDER)
    )

    write_csv(
        out / "primary_category_distribution.csv",
        ["category", "count", "percent_of_cases"],
        ({
            "category": cat,
            "count": primary_category_counts[cat],
            "percent_of_cases": pct(primary_category_counts[cat], n),
        } for cat in CATEGORY_ORDER)
    )

    # 8) LaTeX HotSpot taxonomy table
    latex_table = make_taxonomy_latex(
        category_counts,
        involved_counts,
    )
    (out / "optimization_taxonomy_table.tex").write_text(
        latex_table,
        encoding="utf-8",
    )

    # 9) Unknown primary
    unknown_primary = [r for r in selected if r["Primary"] == "Unknown"]

    unknown_fields = [
        "ID", "Compiler_Tier", "Faulty_Compiler_Component",
        "Optimization_Mapping_Reason", "Optimization_Evidence_Basis",
        "Optimization_Confidence", "Optimization_Unknown_Reason",
        "Type_Mechanism", "Root_Cause_In_Optimization",
        "Root_Cause_Summary", "Uncertainty",
    ]

    def unknown_row(r):
        return {
            "ID": r["ID"],
            "Compiler_Tier": r["Tier"],
            "Faulty_Compiler_Component": r["Faulty_Compiler_Component"],
            "Optimization_Mapping_Reason": r["Optimization_Mapping_Reason"],
            "Optimization_Evidence_Basis": r["Optimization_Evidence_Basis"],
            "Optimization_Confidence": r["Optimization_Confidence"],
            "Optimization_Unknown_Reason": r["Optimization_Unknown_Reason"],
            "Type_Mechanism": r["Type_Mechanism"],
            "Root_Cause_In_Optimization": r["Root_Cause_In_Optimization"],
            "Root_Cause_Summary": r["Root_Cause_Summary"],
            "Uncertainty": r["Uncertainty"],
        }

    # Backward-compatible full Unknown list.
    write_csv(
        out / "unknown_primary_cases.csv",
        unknown_fields,
        (unknown_row(r) for r in unknown_primary),
    )

    # Explicit analysis-ready exports for taxonomy refinement vs. evidence gaps.
    write_csv(
        out / "unknown_optimization_cases.csv",
        unknown_fields,
        (unknown_row(r) for r in unknown_primary),
    )
    write_csv(
        out / "unknown_notaxonomy_cases.csv",
        unknown_fields,
        (
            unknown_row(r)
            for r in unknown_primary
            if r["Optimization_Unknown_Reason"] == "NoTaxonomyMatch"
        ),
    )
    write_csv(
        out / "unknown_insufficient_evidence_cases.csv",
        unknown_fields,
        (
            unknown_row(r)
            for r in unknown_primary
            if r["Optimization_Unknown_Reason"] == "InsufficientEvidence"
        ),
    )

    if malformed:
        write_csv(
            out / "malformed_results.csv",
            ["case_dir", "reason"],
            ({"case_dir": c, "reason": reason} for c, reason in malformed)
        )

    summary = {
        "stage2_results_found": len(records),
        "selected_stage2_population": n,
        "selected_labels": args.labels,
        "screen_labels_among_stage2_cases": dict(screen_counts),
        "analyze_labels": dict(analyze_counts),
        "screen_to_analyze_transition": {
            f"{s}->{a}": c for (s, a), c in transitions.items()
        },
        "compiler_tier_distribution": dict(tier_counts),
        "primary_faulty_optimization_distribution": dict(primary_counts),
        "component_postprocessed_cases": len(postprocessed),
        "component_postprocessed_distribution": dict(postprocessed_counts),
        "optimization_evidence_basis_distribution": dict(optimization_evidence_counts),
        "optimization_confidence_distribution": dict(optimization_confidence_counts),
        "unknown_optimization_reason_distribution": dict(unknown_reason_counts),
        "unknown_primary_cases": len(unknown_primary),
        "unknown_notaxonomy_cases": sum(
            1 for r in unknown_primary
            if r["Optimization_Unknown_Reason"] == "NoTaxonomyMatch"
        ),
        "unknown_insufficient_evidence_cases": sum(
            1 for r in unknown_primary
            if r["Optimization_Unknown_Reason"] == "InsufficientEvidence"
        ),
        "interacting_optimization_distribution": dict(interacting_counts),
        "involved_optimization_distribution": dict(involved_counts),
        "category_case_distribution": {
            cat: category_counts[cat] for cat in CATEGORY_ORDER
        },
        "primary_category_distribution": {
            cat: primary_category_counts[cat] for cat in CATEGORY_ORDER
        },
        "taxonomy_policy": {
            "vocabulary": "official HotSpot closed taxonomy",
            "other_unmapped_labels": "normalized to Unknown",
        },
    }
    (out / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # Console
    print("=" * 82)
    print("TypeFuzz JIT LLM Analysis Statistics")
    print("=" * 82)
    print(f"Stage-2 results found : {len(records)}")
    print(f"Selected Stage-2 N    : {n}")
    print(f"Malformed             : {len(malformed)}")

    print("\nStage-1 -> Stage-2 transitions:")
    for (s, a), c in transitions.most_common():
        print(f"  {s:<8} -> {a:<8} {c:>5} ({pct(c, len(records)):6.2f}%)")

    print("\nCompiler tier:")
    for k, v in tier_counts.most_common():
        print(f"  {k:<12} {v:>5} ({pct(v, n):6.2f}%)")

    print("\nPrimary faulty optimization (top 20):")
    for k, v in primary_counts.most_common(20):
        print(f"  {k:<48} {v:>5} ({pct(v, n):6.2f}%)")

    print(f"\nComponent->taxonomy postprocessed cases: {len(postprocessed)}")
    for k, v in postprocessed_counts.most_common():
        print(f"  {k:<48} {v:>5} ({pct(v, len(postprocessed)):6.2f}%)")

    print("\nOptimization evidence basis:")
    for k, v in optimization_evidence_counts.most_common():
        print(f"  {k:<20} {v:>5} ({pct(v, n):6.2f}%)")

    print("\nOptimization confidence:")
    for k, v in optimization_confidence_counts.most_common():
        print(f"  {k:<20} {v:>5} ({pct(v, n):6.2f}%)")

    if unknown_reason_counts:
        unknown_n = sum(unknown_reason_counts.values())
        print("\nUnknown primary reasons:")
        for k, v in unknown_reason_counts.most_common():
            print(f"  {k:<24} {v:>5} ({pct(v, unknown_n):6.2f}%)")

    print("\nInteracting optimization (top 20):")
    for k, v in interacting_counts.most_common(20):
        print(f"  {k:<48} {v:>5} ({pct(v, n):6.2f}%)")

    print("\nInvolved optimization = Primary OR Interacting (top 20):")
    for k, v in involved_counts.most_common(20):
        print(f"  {k:<48} {v:>5} ({pct(v, n):6.2f}%)")

    print("\nOfficial HotSpot taxonomy — involved-pass case counts:")
    print(
        render_taxonomy_console(
            category_counts,
            involved_counts,
            n,
        )
    )

    print(
        f"\nLaTeX taxonomy table     : "
        f"{out / 'optimization_taxonomy_table.tex'}"
    )
    print(f"Outputs                  : {out}")
    print("=" * 82)


if __name__ == "__main__":
    main()
