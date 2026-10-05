#!/bin/bash

set -e

# Suppress Perl locale warnings.
export LC_ALL=C
export LANG=C

# Configuration
BUILD_DIR="build/linux-x86_64-server-release"
OUTPUT_DIR="coverage_results"
BRANCH_COV=0  # 1 enables branch coverage; 0 disables it.

# First-level directories under src/hotspot/share/ to include in the component report.
COMPONENTS_LIST="c1 opto ci interpreter runtime gc classfile memory"

if ! command -v lcov &> /dev/null; then
    echo "Error: lcov was not found. Install it first (apt-get install lcov)."
    exit 1
fi

if [[ ! -d "$BUILD_DIR" ]]; then
    echo "Error: build directory '$BUILD_DIR' does not exist."
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
cd "$OUTPUT_DIR"
OUTPUT_ABS=$(pwd)
cd - > /dev/null

LCOV_RC=""
if [[ $BRANCH_COV -eq 1 ]]; then
    LCOV_RC="--rc lcov_branch_coverage=1"
fi

HOTSPOT_FILE="$OUTPUT_ABS/hotspot_src.info"
INIT_FILE="$OUTPUT_ABS/init.info"
COVER_FILE="$OUTPUT_ABS/cover.info"
TOTAL_FILE="$OUTPUT_ABS/total.info"

SKIP_COLLECT=0

# Reuse the existing report when no newer coverage data is available.
if [[ -f "$HOTSPOT_FILE" ]]; then
    NEWER_GCDA=$(find "$BUILD_DIR" -name "*.gcda" -newer "$HOTSPOT_FILE" 2>/dev/null | head -n 1)
    
    if [[ -n "$NEWER_GCDA" ]]; then
        echo "=========================================="
        echo "🔄 New .gcda data was generated after the previous report. Recollecting coverage..."
        echo "=========================================="
    else
        echo "=========================================="
        echo "ℹ️  An existing coverage report was found at $HOTSPOT_FILE, with no newer test data."
        echo "Skipping collection and displaying the existing results..."
        echo "=========================================="
        SKIP_COLLECT=1
    fi
fi

if [[ $SKIP_COLLECT -eq 0 ]]; then
    GCDA_COUNT=$(find "$BUILD_DIR" -name "*.gcda" 2>/dev/null | wc -l)
    if [[ $GCDA_COUNT -eq 0 ]]; then
        echo "❌ Error: no .gcda files were found in the build directory."
        echo "Run the experiment before executing this script."
        exit 1
    fi

    echo "=========================================="
    echo "Found $GCDA_COUNT .gcda files. Collecting coverage..."
    echo "=========================================="

    if [[ ! -f "$INIT_FILE" ]]; then
        echo "[1/4] Capturing the zero-coverage baseline (init.info)..."
        lcov -c -i -d "$BUILD_DIR" -o "$INIT_FILE" $LCOV_RC > /dev/null 2>&1
    else
        echo "[1/4] Reusing the existing baseline (init.info)..."
    fi

    echo "[2/4] Capturing coverage from this experiment (cover.info)..."
    lcov -c -d "$BUILD_DIR" -o "$COVER_FILE" $LCOV_RC > /dev/null 2>&1

    echo "[3/4] Merging data and extracting HotSpot source coverage..."
    lcov -a "$INIT_FILE" -a "$COVER_FILE" -o "$TOTAL_FILE" $LCOV_RC > /dev/null 2>&1
    lcov -e "$TOTAL_FILE" '*/src/hotspot/*' -o "$HOTSPOT_FILE" $LCOV_RC > /dev/null 2>&1

    echo "✅ Coverage data collected and saved to $HOTSPOT_FILE."

    echo "=========================================="
    read -p "Coverage collection is complete. Clear the .gcda counters for the next experiment? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "🧹 Clearing the existing .gcda counters..."
        lcov --zerocounters -d "$BUILD_DIR" > /dev/null 2>&1
        echo "The build directory counters have been cleared."
    else
        echo "Keeping the current .gcda data. Coverage will accumulate unless the counters are cleared before the next experiment."
    fi
    echo "=========================================="
fi

if [[ ! -f "$HOTSPOT_FILE" ]]; then
    echo "❌ Error: coverage report $HOTSPOT_FILE was not found."
    exit 1
fi

TMP_LCAP=$(mktemp)
lcov -l "$HOTSPOT_FILE" $LCOV_RC > "$TMP_LCAP" 2>/dev/null

echo "📊 HotSpot source coverage summary:"
head -n 10 "$TMP_LCAP"
TOTAL_LINE=$(grep "Total:" "$TMP_LCAP" | tail -1)
[[ -n "$TOTAL_LINE" ]] && echo "$TOTAL_LINE" || tail -1 "$TMP_LCAP"
rm -f "$TMP_LCAP"

echo ""
read -p "Display the coverage breakdown for the configured components? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo ""
    printf "%-15s %-12s %-12s %-12s\n" "Component" "Lines(%)" "Functions(%)" "Branches(%)"
    printf "%-15s %-12s %-12s %-12s\n" "---------" "---------" "-----------" "-----------"

    TMP_DIR="/tmp/coverage_comp_$$"
    mkdir -p "$TMP_DIR"

    for comp in $COMPONENTS_LIST; do
        TMP_INFO="$TMP_DIR/${comp}.info"
        lcov -e "$HOTSPOT_FILE" "*/src/hotspot/share/$comp/*" -o "$TMP_INFO" $LCOV_RC > /dev/null 2>&1

        SUM=$(lcov -l "$TMP_INFO" $LCOV_RC 2>/dev/null | grep "Total:" | tail -1)
        if [[ -n "$SUM" ]]; then
            lines_pct=$(echo "$SUM" | awk -F'|' '{print $2}' | sed 's/^ *//;s/ .*//;s/%//')
            funcs_pct=$(echo "$SUM" | awk -F'|' '{print $3}' | sed 's/^ *//;s/ .*//;s/%//')
            branches_pct="-"
            if [[ $BRANCH_COV -eq 1 ]]; then
                branches_pct=$(echo "$SUM" | awk -F'|' '{print $4}' | sed 's/^ *//;s/ .*//;s/%//')
            fi
            printf "%-15s %-12s %-12s %-12s\n" "$comp" "${lines_pct}%" "${funcs_pct}%" "${branches_pct}%"
        else
            printf "%-15s %-12s %-12s %-12s\n" "$comp" "N/A" "N/A" "N/A"
        fi
    done
    rm -rf "$TMP_DIR"
fi

echo "=========================================="
echo "Done."
