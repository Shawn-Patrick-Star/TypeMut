from __future__ import annotations

from typing import Any, Dict, Tuple

from .evidence_common import EvidenceConfig
from .hotspot_optimization_taxonomy import (
    format_optimization_taxonomy,
    normalize_analysis_result,
    validate_optimization_fields,
)


# ---------------------------------------------------------------------------
# JIT-specific evidence preferences
# ---------------------------------------------------------------------------

JIT_DIAGNOSTIC_TERMS = (
    "root cause", "regression", "regressed", "introduced", "bisect",
    "fix", "fixed", "patch", "commit", "pull request", "reproducer",
    "reproduce", "workaround", "crash", "assert", "assertion", "fatal",
    "segfault", "SIGSEGV", "exception", "wrong code", "miscompile",
    "incorrect result", "stack trace", "failure", "fails", "failed",
    "C1", "C2", "JIT", "compiler", "optimization", "deopt", "deoptimization",
    # Type-participation evidence. Type can be part of a trigger without being
    # the compiler defect's ultimate root cause.
    "type", "BasicType", "TypeInt", "TypeLong", "TypePtr", "TypeOopPtr",
    "klass", "subtype", "checkcast", "instanceof", "cast", "narrow",
    "widen", "truncate", "sign extend", "sign-extension", "zero extend",
    "zero-extension", "signed", "unsigned", "range", "lattice", "meet",
    "join", "Phi", "receiver", "profile", "speculative", "MethodHandle",
    "MethodType", "boxing", "unboxing", "primitive", "reference",
    "array element",
)

JIT_PATCH_PATH_TERMS = (
    "src/hotspot/",
    "/opto/",
    "/c1/",
    "/c2/",
    "/compiler/",
    "optimizer",
    "codegen",
)

# Hunk-level terms help retain the most diagnostic compiler edits when a large
# fixing patch must be reduced to the evidence budget.
JIT_PATCH_HUNK_TERMS = (
    "PhaseIdealLoop", "SuperWord", "PhaseGVN", "PhaseIterGVN",
    "ConnectionGraph", "Compile::", "Matcher::", "GraphKit",
    "TypeInt", "TypeLong", "TypePtr", "TypeOopPtr", "BasicType", "Klass",
    "subtype", "checkcast", "instanceof", "narrow", "widen",
    "sign_extend", "zero_extend", "range_check", "RangeCheck",
    "unroll", "peel", "vector", "inline", "escape", "devirtual",
    "constant fold", "common subexpression", "dominat", "safepoint",
)


COMPILER_TIERS = {
    "C1",
    "C2",
    "Shared",
    "Unknown",
}


# ---------------------------------------------------------------------------
# Stage 1: cheap, high-recall type-related screening.
# No optimization analysis here.
# ---------------------------------------------------------------------------

SCREEN_PROMPT = r"""
You are screening HotSpot JIT/compiler bug candidates for TYPE-RELATEDNESS.

SCOPE
- The input corpus has already been collected from HotSpot compiler/JIT issues.
- Do NOT reclassify whether an issue belongs to the JIT corpus.
- Analyze each CASE independently.
- The goal of this stage is HIGH RECALL: clearly irrelevant cases may be No,
  but if compact evidence is insufficient to safely exclude meaningful type
  participation, use Unclear instead of forcing No.

TYPE-RELATED CRITERION
In this study, "type-related" does NOT mean that the program type itself must be
the ultimate root cause of the compiler defect.

A JIT bug is type-related when program type information or type-dependent
semantics makes a meaningful causal contribution to triggering, reproducing,
exposing, suppressing, or changing the observed compiler failure.

Type participation is sufficient; type root-causality is not required.

Use this counterfactual as a screening aid:
"If the relevant program type were changed while the surrounding program
structure stayed reasonably similar, would whether/how the failure reproduces
plausibly change because the compiler path, optimization eligibility, type facts,
representations, conversions, or type-dependent semantics changed?"

Potentially type-related mechanisms include, but are not limited to:
- primitive kind, width, sign, representation, or conversions;
- narrowing/widening, sign/zero extension, casts, boxing/unboxing;
- type-flow/lattice facts, merging, propagation, refinement, or range/type facts;
- subtype/checkcast/instanceof/class-hierarchy reasoning;
- receiver-type profiling or speculative type information;
- MethodType / MethodHandle / invokedynamic adaptation;
- vector/scalar element-type inconsistencies;
- type-dependent array/reference/value handling;
- a type choice enabling, disabling, or materially changing an optimization or
  compiler path that exposes the defect.

These are examples only. Do NOT force a case into a predefined defect pattern.

Do NOT mark Yes merely because source code necessarily contains int, long,
casts, arrays, inheritance, interfaces, or generics. There must be evidence
that the particular type choice participates in the trigger, manifestation,
compiler path, or failure.

Do NOT require the fixing patch itself to modify type-handling code. The
compiler defect may live in another optimization while type is part of the
trigger condition.

Usually No:
- the available evidence supports a compiler defect whose trigger and behavior
  are independent of the relevant program type;
- types merely occur in the testcase with no evidence that changing them
  affects the compiler path or failure;
- GC/concurrency/native-I/O failures unrelated to compiler type-dependent
  behavior.

OUTPUT
Return ONLY one valid JSON object:

{
  "results": [
    {
      "ID": "<exact CASE_ID>",
      "Type_Related": "Yes | No | Unclear",
      "Reason": "<concise technical reason, preferably <= 80 words>"
    }
  ]
}

Return exactly one result for every supplied CASE.
Use each CASE_ID exactly once.
""".strip()


# ---------------------------------------------------------------------------
# Stage 2: detailed type-related + concrete optimization analysis.
#
# IMPORTANT:
# The LLM identifies concrete passes/transformations only.
# It does NOT classify them into paper-level optimization categories.
# Category aggregation is performed deterministically later by statistics code.
# ---------------------------------------------------------------------------

ANALYZE_PROMPT = (r"""
You are performing detailed evidence analysis of HotSpot JIT/compiler bug candidates
that passed an earlier high-recall type-related screen.

Re-evaluate every CASE from the supplied evidence. Do NOT assume the previous screen
was correct. Analyze each CASE independently and never transfer evidence between cases.

The input corpus is already restricted to HotSpot compiler/JIT issues. Do NOT
reclassify JIT membership.

======================================================================
PART A — TYPE-RELATEDNESS
======================================================================

Re-evaluate type-relatedness from the supplied evidence. The previous screen was
high-recall and is not authoritative.

In this study, type-related means that program type information or
type-dependent semantics has a meaningful causal contribution to triggering,
reproducing, exposing, suppressing, or changing the observed compiler failure.
The type itself does NOT need to be the ultimate root cause or the location of
the compiler defect.

A case can therefore be Yes when a type choice changes optimization eligibility,
IR shape, compiler path, or another trigger condition that exposes a compiler
defect elsewhere.

Use:
- Yes: evidence supports meaningful type participation in the trigger,
  manifestation, compiler path, optimization eligibility, or failure.
- No: evidence adequately supports a type-independent trigger/root mechanism,
  and types are only incidental testcase contents.
- Unclear: type participation is plausible, but the supplied evidence does not
  establish or safely rule it out.

Potential mechanisms include:
- primitive kind/width/sign/representation and conversions;
- narrowing/widening, sign/zero extension, casts, boxing/unboxing;
- type-flow/lattice facts, merging, propagation, refinement, and range facts;
- subtype/checkcast/instanceof/class-hierarchy reasoning;
- receiver type profiles and speculative type information;
- MethodType / MethodHandle / invokedynamic adaptation;
- vector/scalar or vector-lane element-type behavior;
- type-dependent array/reference/value handling;
- a type choice enabling, disabling, or materially changing an optimization.

Do NOT mark Yes merely because the testcase contains types. Identify the
specific type choice or type-dependent fact that participates.

Do NOT require the patch to modify type-handling code for a Yes. A patch may
fix Loop Unrolling, Vectorization, Inlining, Escape Analysis, or another pass
while the program type remains an essential trigger.

======================================================================
PATCH-FIRST ANALYSIS PROCEDURE
======================================================================

When a fixing patch is supplied, treat the HotSpot compiler-source changes as
the strongest available evidence. Before deciding the output fields, inspect
the patch and determine internally:

1. Which HotSpot source file(s) are modified?
2. Which compiler class, phase, function, or symbol is modified?
3. What compiler state, condition, assumption, transformation, or invariant
   was wrong before the fix?
4. What behavior does the patch change?
5. Does the changed logic or trigger involve type information or
   type-dependent behavior?
6. Which concrete compiler optimization/transformation is most directly
   implicated by the changed code?

Pay particular attention to:
- BasicType, Type, TypeInt, TypeLong, TypePtr, TypeOopPtr, Klass, subtype,
  ranges, signedness, casts, conversions, receiver/profile information;
- compiler phase/class/function names;
- assertions/invariants removed, added, or strengthened;
- comments added by the fix;
- changed IR-node handling;
- optimization-specific source files and functions.

Prefer compiler-source changes over regression-test-only changes. A patch does
not need to literally contain a taxonomy optimization name: infer the compiler
operation from the modified component and the semantics of the change.

======================================================================
PART B — HOTSPOT COMPILER TIER
======================================================================

Identify the HotSpot compiler tier from explicit evidence.

Compiler_Tier must be EXACTLY one of:
- "C1"
- "C2"
- "Shared"
- "Unknown"

Definitions:
- C1: evidence clearly attributes the faulty compiler behavior to C1.
- C2: evidence clearly attributes the faulty compiler behavior to C2.
- Shared: the faulty compiler mechanism is explicitly shared by C1 and C2, or
  the available evidence shows the same compiler defect belongs to shared
  HotSpot compiler infrastructure rather than one tier.
- Unknown: evidence is insufficient to distinguish C1, C2, or Shared.

Do NOT force C1/C2 merely because the issue is a HotSpot JIT bug.

======================================================================
PART C — CONCRETE OPTIMIZATION / TRANSFORMATION ANALYSIS
======================================================================

Your job is to identify the CONCRETE compiler transformation/pass involved.
Do NOT classify the result into broad paper-level optimization categories.
The researchers will map concrete pass names to categories later using code.

Use a two-step reasoning process:
1. identify the actual HotSpot compiler component/operation from patch, issue,
   logs, and source symbols;
2. map that implementation-level operation to the semantically corresponding
   optimization in the closed taxonomy.

The following HotSpot optimization taxonomy is a CLOSED VOCABULARY. Both
Primary_Faulty_Optimization and every Interacting_Optimizations entry MUST use
one exact optimization name from this list. However, an exact textual match is
NOT required: HotSpot patches normally use implementation class/function names.
When the evidence clearly identifies an implementation operation that
semantically corresponds to a taxonomy item, emit the taxonomy item and preserve
the implementation-level evidence in Faulty_Compiler_Component and
Optimization_Mapping_Reason.

Required terminology mapping:
- SuperWord, SLP, C2 SuperWord, and auto-vectorization -> Loop Vectorization

Semantic mapping examples:
- an explicit loop-unrolling routine or do_unroll fix -> Loop Unrolling;
- an explicit loop-peeling routine -> Loop Peeling;
- ConnectionGraph/escape-state fixes clearly implementing escape analysis
  -> Escape Analysis;
- explicit range-check elimination logic -> Range Check Elimination.

These examples illustrate semantic mapping; they are not sufficient evidence by
themselves. Do not map from a broad file name alone when multiple optimizations
live in that file.

Use Unknown only when:
1. the faulty compiler operation cannot be identified from the supplied
   evidence; or
2. the identified operation genuinely has no defensible match in the supplied
   taxonomy.

Do NOT use Unknown merely because a patch uses an internal HotSpot class,
function, phase, or implementation term instead of taxonomy wording.

""" + format_optimization_taxonomy() + r"""

Evidence priority:
1. fixing patch and changed compiler function/file;
2. explicit root-cause discussion in the issue;
3. compiler assertion / stack trace / compilation log;
4. only then testcase shape.

Never infer an optimization solely because the Java testcase has a syntactic
shape commonly associated with it.

-------------------------
Primary_Faulty_Optimization
-------------------------

Identify the SINGLE concrete optimization/transformation/pass that is best
supported as the compiler operation most directly responsible for creating,
transforming, or mishandling the faulty state.

Use the fixing patch to localize the compiler component whenever possible. Then
map the component's semantics to the closed taxonomy. You MUST select the final
value from the taxonomy above. Use "Unknown" only under the explicit Unknown
rules above; never invent or emit an unlisted taxonomy name.

-------------------------
Interacting_Optimizations
-------------------------

List other concrete optimizations/transformation passes that materially participate
in creating the trigger condition or interact with the primary faulty optimization,
but are not themselves the best-supported primary fault location.

Examples:
- Loop Unrolling creates a loop shape later mishandled by Loop Vectorization.
- Range Check Elimination changes constraints that interact with Loop Peeling.
- Inlining (Graph Integration) exposes code later mishandled by Common
  Subexpression Elimination.

Every entry MUST be selected from the closed taxonomy above.
Do NOT list a pass merely because it probably ran.
Use [] when no listed interacting optimization is supported by evidence. Do not
put "Unknown" in Interacting_Optimizations.

======================================================================
EVIDENCE RULES
======================================================================

- Prefer a clearly identified fixing patch as the strongest compiler-mechanism
  evidence, especially modified HotSpot source file + symbol/function.
- Distinguish the faulty compiler component from the taxonomy label. Preserve
  the component even when the taxonomy result is Unknown.
- A patch is not required.
- Do not invent compiler internals or optimization passes.
- Cite at most 2 small decisive Java snippets.
- Each Java snippet must be <= 300 characters. Never copy a whole method/class.
- Cite at most 2 patch locations/hunks.
- For patch evidence, report only file + symbol/location + a short explanation.
  Do NOT reproduce patch hunks or long added/deleted code in the output.
- Keep every explanation concise because this analysis is performed at scale.
- Interacting_Optimizations should normally contain 0-3 entries and must contain
  only materially supported interactions, not passes that merely may have run.

======================================================================
OUTPUT
======================================================================

Return ONLY one valid JSON object:

{
  "results": [
    {
      "ID": "<exact CASE_ID>",

      "Type_Related": "Yes | No | Unclear",
      "Type_Related_Reason": "<concise technical reason, <= 60 words>",
      "Type_Mechanism": "<short free-text mechanism, <= 25 words; use N/A for a clear No>",

      "Compiler_Tier": "C1 | C2 | Shared | Unknown",

      "Faulty_Compiler_Component": "<HotSpot file/class/function/phase or Unknown>",
      "Primary_Faulty_Optimization": "<exact name from the closed taxonomy, or Unknown>",
      "Interacting_Optimizations": [],
      "Optimization_Mapping_Reason": "<= 45 words connecting evidence/component to taxonomy, or Unknown>",
      "Optimization_Evidence_Basis": "Patch | Issue | Log | Testcase | Mixed | Unknown",
      "Optimization_Confidence": "High | Medium | Low",
      "Optimization_Unknown_Reason": "N/A | InsufficientEvidence | NoTaxonomyMatch",

      "Root_Cause_In_Optimization": "<= 35 words explaining how the optimization failed, or Unknown>",

      "Java_Evidence": [
        {
          "file": "",
          "snippet": "",
          "why_relevant": ""
        }
      ],

      "Patch_Evidence": [
        {
          "file": "",
          "location_or_symbol": "",
          "why_relevant": ""
        }
      ],

      "Root_Cause_Summary": "<concise overall root-cause summary, <= 60 words>",
      "Alternative_Explanation": "<strongest plausible non-type explanation, <= 30 words, or None>",
      "Uncertainty": "<missing/ambiguous evidence, <= 30 words, or None>"
    }
  ]
}

STRICT CONSTRAINTS
- Return exactly one result for every supplied CASE.
- Use each supplied CASE_ID exactly once.
- Type_Related must be exactly Yes, No, or Unclear.
- Compiler_Tier must be exactly C1, C2, Shared, or Unknown.
- Interacting_Optimizations must be a JSON list of strings.
- Primary_Faulty_Optimization must be an exact taxonomy name or Unknown.
- Faulty_Compiler_Component should preserve the implementation-level component
  identified from evidence, even when Primary_Faulty_Optimization is Unknown.
- Optimization_Evidence_Basis must be Patch, Issue, Log, Testcase, Mixed, or Unknown.
- Optimization_Confidence must be High, Medium, or Low.
- Optimization_Unknown_Reason must be N/A, InsufficientEvidence, or NoTaxonomyMatch.
- If Primary_Faulty_Optimization is not Unknown, Optimization_Unknown_Reason must be N/A.
- If Primary_Faulty_Optimization is Unknown, distinguish lack of evidence
  (InsufficientEvidence) from an identified compiler operation outside the
  taxonomy (NoTaxonomyMatch).
- Every Interacting_Optimizations entry must be an exact taxonomy name; if none
  applies, return [].
- Do NOT output Optimization_Categories.
- Interacting_Optimizations: at most 4 entries.
- Java_Evidence: at most 2 entries; every snippet <= 300 characters.
- Patch_Evidence: at most 2 entries; do not reproduce patch hunks.
- Brevity is mandatory. Do not repeat the same explanation across fields.
- Do not include markdown fences or prose outside the JSON object.
""").strip()


# ---------------------------------------------------------------------------
# Evidence budgets
# ---------------------------------------------------------------------------

SCREEN_CFG = EvidenceConfig(
    description_chars=5000,
    comments_chars_with_patch=3500,
    comments_chars_without_patch=6500,
    java_chars_with_patch=9000,
    java_chars_without_patch=14000,
    patch_chars=12000,
    max_comments=7,
    diagnostic_terms=JIT_DIAGNOSTIC_TERMS,
    patch_path_priority_terms=JIT_PATCH_PATH_TERMS,
    patch_hunk_priority_terms=JIT_PATCH_HUNK_TERMS,
)

ANALYZE_CFG = EvidenceConfig(
    description_chars=8000,
    comments_chars_with_patch=6000,
    comments_chars_without_patch=12000,
    java_chars_with_patch=12000,
    java_chars_without_patch=24000,
    patch_chars=23000,
    max_comments=9,
    diagnostic_terms=JIT_DIAGNOSTIC_TERMS,
    patch_path_priority_terms=JIT_PATCH_PATH_TERMS,
    patch_hunk_priority_terms=JIT_PATCH_HUNK_TERMS,
)


# ---------------------------------------------------------------------------
# Output validation
# ---------------------------------------------------------------------------

def validate_screen(obj: Dict[str, Any], case_id: str) -> Tuple[bool, str]:
    if str(obj.get("ID", "")).strip() != case_id:
        return False, "wrong ID"

    if obj.get("Type_Related") not in {"Yes", "No", "Unclear"}:
        return False, "invalid Type_Related"

    if not isinstance(obj.get("Reason"), str):
        return False, "missing Reason"

    return True, "ok"


def validate_analyze(obj: Dict[str, Any], case_id: str) -> Tuple[bool, str]:
    required = {
        "ID",
        "Type_Related",
        "Type_Related_Reason",
        "Type_Mechanism",
        "Compiler_Tier",
        "Faulty_Compiler_Component",
        "Primary_Faulty_Optimization",
        "Interacting_Optimizations",
        "Optimization_Mapping_Reason",
        "Optimization_Evidence_Basis",
        "Optimization_Confidence",
        "Optimization_Unknown_Reason",
        "Root_Cause_In_Optimization",
        "Java_Evidence",
        "Patch_Evidence",
        "Root_Cause_Summary",
        "Alternative_Explanation",
        "Uncertainty",
    }

    missing = required - set(obj)
    if missing:
        return False, f"missing keys: {sorted(missing)}"

    if str(obj.get("ID", "")).strip() != case_id:
        return False, "wrong ID"

    if obj.get("Type_Related") not in {"Yes", "No", "Unclear"}:
        return False, "invalid Type_Related"

    if obj.get("Compiler_Tier") not in COMPILER_TIERS:
        return False, "invalid Compiler_Tier"

    if not isinstance(obj.get("Faulty_Compiler_Component"), str):
        return False, "Faulty_Compiler_Component must be string"

    if not isinstance(obj.get("Optimization_Mapping_Reason"), str):
        return False, "Optimization_Mapping_Reason must be string"

    if obj.get("Optimization_Evidence_Basis") not in {
        "Patch", "Issue", "Log", "Testcase", "Mixed", "Unknown",
    }:
        return False, "invalid Optimization_Evidence_Basis"

    if obj.get("Optimization_Confidence") not in {"High", "Medium", "Low"}:
        return False, "invalid Optimization_Confidence"

    if obj.get("Optimization_Unknown_Reason") not in {
        "N/A", "InsufficientEvidence", "NoTaxonomyMatch",
    }:
        return False, "invalid Optimization_Unknown_Reason"

    if not isinstance(obj.get("Primary_Faulty_Optimization"), str):
        return False, "Primary_Faulty_Optimization must be string"

    if not isinstance(obj.get("Interacting_Optimizations"), list):
        return False, "Interacting_Optimizations must be list"

    if any(not isinstance(x, str) for x in obj["Interacting_Optimizations"]):
        return False, "Interacting_Optimizations entries must be strings"

    if not isinstance(obj.get("Java_Evidence"), list):
        return False, "Java_Evidence must be list"

    if not isinstance(obj.get("Patch_Evidence"), list):
        return False, "Patch_Evidence must be list"

    # Enforce the closed HotSpot vocabulary in code as well as in the prompt.
    # Unsupported primary names become Unknown; unsupported interactions are
    # removed because [] represents no supported interacting optimization.
    normalize_analysis_result(obj)
    if not validate_optimization_fields(obj):
        return False, "invalid optimization taxonomy value"

    primary = obj.get("Primary_Faulty_Optimization")
    unknown_reason = obj.get("Optimization_Unknown_Reason")
    if primary == "Unknown" and unknown_reason == "N/A":
        return False, "Unknown primary requires Optimization_Unknown_Reason"
    if primary != "Unknown" and unknown_reason != "N/A":
        return False, "known primary requires Optimization_Unknown_Reason=N/A"

    # Defensive normalization in case a model ignores the prompt's size limits.
    obj["Interacting_Optimizations"] = obj["Interacting_Optimizations"][:4]
    obj["Java_Evidence"] = obj["Java_Evidence"][:2]
    obj["Patch_Evidence"] = obj["Patch_Evidence"][:2]

    for evidence in obj["Java_Evidence"]:
        if isinstance(evidence, dict):
            snippet = evidence.get("snippet")
            if isinstance(snippet, str) and len(snippet) > 300:
                evidence["snippet"] = snippet[:300] + "..."
            why = evidence.get("why_relevant")
            if isinstance(why, str) and len(why) > 600:
                evidence["why_relevant"] = why[:600] + "..."

    for evidence in obj["Patch_Evidence"]:
        if isinstance(evidence, dict):
            why = evidence.get("why_relevant")
            if isinstance(why, str) and len(why) > 600:
                evidence["why_relevant"] = why[:600] + "..."

    return True, "ok"
