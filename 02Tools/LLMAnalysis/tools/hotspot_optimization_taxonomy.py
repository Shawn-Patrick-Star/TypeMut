"""Official HotSpot optimization vocabulary used by LLManalyze.

The vocabulary is intentionally closed. Implementation aliases are normalized
only when their mapping to a taxonomy item is unambiguous.
"""

from typing import Any, Dict, List
import re

HOTSPOT_OPTIMIZATION_TAXONOMY = {
    "compiler_tactics": [
        "Delayed Compilation", "Tiered Compilation", "On-Stack Replacement",
        "Delayed Reoptimization", "Program Dependence Graph Representation",
        "Static Single Assignment Representation",
    ],
    "speculative": [
        "Optimistic Nullness Assertions", "Optimistic Type Assertions",
        "Optimistic Type Strengthening", "Optimistic Array Length Strengthening",
        "Untaken Branch Pruning", "Optimistic N-Morphic Inlining",
        "Branch Frequency Prediction", "Call Frequency Prediction",
    ],
    "proof_based": [
        "Exact Type Inference", "Memory Value Inference", "Memory Value Tracking",
        "Constant Folding", "Reassociation", "Operator Strength Reduction",
        "Null Check Elimination", "Type Test Strength Reduction",
        "Type Test Elimination", "Algebraic Simplification",
        "Common Subexpression Elimination", "Global Value Numbering",
        "Integer Range Typing",
    ],
    "flow_sensitive": [
        "Conditional Constant Propagation", "Dominating Test Detection",
        "Flow-Carried Type Narrowing", "Dead Code Elimination",
    ],
    "language_specific": [
        "Class Hierarchy Analysis", "Devirtualization", "Symbolic Constant Propagation",
        "Autobox Elimination", "Escape Analysis", "Lock Elision",
        "Lock Fusion", "De-Reflection",
    ],
    "memory_and_placement": [
        "Expression Hoisting", "Expression Sinking", "Redundant Store Elimination",
        "Adjacent Store Fusion", "Card-Mark Elimination", "Merge-Point Splitting",
    ],
    "loop_transformations": [
        "Loop Unrolling", "Loop Peeling", "Loop Unswitching",
        "Loop Predication", "Loop Invariant Code Motion",
        "Safepoint Elimination", "Iteration Range Splitting",
        "Range Check Elimination", "Loop Vectorization",
    ],
    "global_code_shaping": [
        "Inlining (Graph Integration)", "Global Code Motion",
        "Heat-Based Code Layout", "Switch Balancing", "Throw Inlining",
        "String Concatenation Optimization", "Control Flow Optimization",
    ],
    "control_flow_graph": [
        "Local Code Scheduling", "Local Code Bundling", "Delay Slot Filling",
        "Graph-Coloring Register Allocation", "Linear Scan Register Allocation",
        "Live Range Splitting", "Copy Coalescing", "Constant Splitting",
        "Copy Removal", "Address Mode Matching", "Instruction Peepholing",
        "DFA-Based Code Generator",
    ],
    "vector_and_language_extensions": [
        "Vector API Optimization", "Macro Logic Optimization",
        "Value Type Optimization",
    ],
}

HOTSPOT_OPTIMIZATION_NAMES = {
    name for group in HOTSPOT_OPTIMIZATION_TAXONOMY.values() for name in group
}
HOTSPOT_OPTIMIZATION_NAMES.add("Unknown")

_NORMALIZED_OPTIMIZATION_NAMES = {
    name.casefold(): name for name in HOTSPOT_OPTIMIZATION_NAMES
}

HOTSPOT_OPTIMIZATION_CATEGORY_LABELS = {
    "compiler_tactics": "Compiler Tactics",
    "speculative": "Speculative (Profile-Based) Techniques",
    "proof_based": "Proof-Based Techniques",
    "flow_sensitive": "Flow-Sensitive Rewrites",
    "language_specific": "Language-Specific Techniques",
    "memory_and_placement": "Memory and Placement Transformation",
    "loop_transformations": "Loop Transformations",
    "global_code_shaping": "Global Code Shaping",
    "control_flow_graph": "Control Flow Graph Transformation",
    "vector_and_language_extensions": "Vector and Language Extensions",
}

# HotSpot implementation terminology -> taxonomy.
# Keep this explicit instead of fuzzy matching.
_IMPLEMENTATION_TERM_ALIASES = {
    "superword": "Loop Vectorization",
    "slp": "Loop Vectorization",
    "c2 superword": "Loop Vectorization",
    "auto-vectorization": "Loop Vectorization",
    "auto vectorization": "Loop Vectorization",

    "phasestringopts": "String Concatenation Optimization",
    "optimizestringconcat": "String Concatenation Optimization",
    "stringbuilder": "String Concatenation Optimization",

    "loop_predication": "Loop Predication",
    "loop predication": "Loop Predication",
    "loopunswitch": "Loop Unswitching",
    "loop unswitching": "Loop Unswitching",
    "licm": "Loop Invariant Code Motion",
    "loop invariant code motion": "Loop Invariant Code Motion",

    "phasegvn": "Global Value Numbering",
    "phaseitergvn": "Global Value Numbering",
    "igvn": "Global Value Numbering",

    "split_if": "Control Flow Optimization",
    "split if": "Control Flow Optimization",

    "phasevector": "Vector API Optimization",
    "vectorbox": "Vector API Optimization",
    "scalarize_vbox": "Vector API Optimization",

    "computelogiccone": "Macro Logic Optimization",
    "macro logic": "Macro Logic Optimization",

    "valuetype": "Value Type Optimization",
    "inlineclass": "Value Type Optimization",
}


# Audited evidence patterns used only for deterministic post-processing of
# LLM results that were originally Unknown/NoTaxonomyMatch. These are
# intentionally narrow and ordered from more specific to more general.
_EVIDENCE_TERM_PATTERNS = (
    (
        re.compile(
            r"\bPhaseStringOpts\b|\bOptimizeStringConcat\b|"
            r"\bStringConcat::(?:build_candidate|validate_control_flow|replace_string_concat)\b",
            re.IGNORECASE,
        ),
        "String Concatenation Optimization",
    ),
    (
        re.compile(
            r"\bloop_predication(?:_impl)?\b|\bloopPredicate\.cpp\b|"
            r"\bloop_predication_should_follow_branches\b",
            re.IGNORECASE,
        ),
        "Loop Predication",
    ),
    (
        re.compile(
            r"\bloopUnswitch\.cpp\b|\bUnswitchCandidate\b|"
            r"\bloop[_ ]?unswitch(?:ing)?\b",
            re.IGNORECASE,
        ),
        "Loop Unswitching",
    ),
    (
        re.compile(
            r"\bC1 LICM\b|\bloop-invariant code motion\b|"
            r"\bloop invariant code motion\b|\bc1_ValueMap\b.*\bhoist",
            re.IGNORECASE,
        ),
        "Loop Invariant Code Motion",
    ),
    (
        re.compile(
            r"\bPhaseIdealLoop::(?:do_split_if|split_up|split_thru_phi)\b|"
            r"\bsplit_if_with_blocks(?:_pre)?\b|\bsplit_thru_phi\b|"
            r"\bIfNode::fold_compares\b",
            re.IGNORECASE,
        ),
        "Control Flow Optimization",
    ),
    (
        re.compile(
            r"\bPhaseVector::(?:expand_vbox_node_helper|scalarize_vbox_node)\b|"
            r"\bVectorBox\b|\bscalarize_vbox\b|\bvector-box scalarization\b|"
            r"\bvector box expansion\b",
            re.IGNORECASE,
        ),
        "Vector API Optimization",
    ),
    (
        re.compile(
            r"\bCompile::compute_(?:logic_cone|truth_table)\b|"
            r"\boptimize_logic_cones\b|\bMacroLogicV?\b|"
            r"\bmacro[- ]logic\b",
            re.IGNORECASE,
        ),
        "Macro Logic Optimization",
    ),
    (
        re.compile(
            r"\bPhaseGVN\b|\bPhaseIterGVN\b|\bIGVN\b|"
            r"\bGVN/IGVN\b",
            re.IGNORECASE,
        ),
        "Global Value Numbering",
    ),
)


def infer_optimization_from_evidence(*values: Any) -> str:
    """Map audited implementation evidence to one taxonomy optimization.

    This helper is deliberately conservative and is intended for
    post-processing cases already classified as Unknown/NoTaxonomyMatch.
    It performs no fuzzy similarity matching and does not map generic
    backend, runtime, or compiler-infrastructure terms.
    """
    text = "\n".join(
        str(value)
        for value in values
        if value not in (None, "", "Unknown")
    )
    if not text:
        return "Unknown"

    for pattern, optimization in _EVIDENCE_TERM_PATTERNS:
        if pattern.search(text):
            return optimization
    return "Unknown"

def format_optimization_taxonomy() -> str:
    sections = []
    for key, names in HOTSPOT_OPTIMIZATION_TAXONOMY.items():
        sections.append(
            f"{HOTSPOT_OPTIMIZATION_CATEGORY_LABELS[key]}:\n" +
            "\n".join(f"- {name}" for name in names)
        )
    return "\n\n".join(sections)


def normalize_optimization_name(name: str) -> str:
    if not isinstance(name, str):
        return "Unknown"
    cleaned = name.strip()
    key = cleaned.casefold()
    if key in _NORMALIZED_OPTIMIZATION_NAMES:
        return _NORMALIZED_OPTIMIZATION_NAMES[key]
    return _IMPLEMENTATION_TERM_ALIASES.get(key, "Unknown")


def normalize_analysis_result(obj: Dict[str, Any]) -> Dict[str, Any]:
    obj["Primary_Faulty_Optimization"] = normalize_optimization_name(
        obj.get("Primary_Faulty_Optimization", "Unknown")
    )
    interactions = obj.get("Interacting_Optimizations", [])
    if not isinstance(interactions, list):
        interactions = []
    normalized: List[str] = []
    for item in interactions:
        name = normalize_optimization_name(item)
        if name != "Unknown" and name not in normalized:
            normalized.append(name)
    obj["Interacting_Optimizations"] = normalized
    return obj


def validate_optimization_fields(obj: Dict[str, Any]) -> bool:
    return (
        obj.get("Primary_Faulty_Optimization") in HOTSPOT_OPTIMIZATION_NAMES
        and isinstance(obj.get("Interacting_Optimizations"), list)
        and all(
            x in HOTSPOT_OPTIMIZATION_NAMES and x != "Unknown"
            for x in obj["Interacting_Optimizations"]
        )
    )
