#!/bin/bash
# Stress test for banked LLC model
# Runs both single-bank and banked LLC with the AXI4 fuzzer at various scales

set -e

SIMDIR=$(cd "$(dirname "$0")" && pwd)
RESULTS_DIR="$SIMDIR/output/llc_test_results"
mkdir -p "$RESULTS_DIR"

SINGLE_BIN="$SIMDIR/generated-src/f2/f2-fasedtests-AXI4Fuzzer-LLCDRAMConfig-DefaultF1Config/VAXI4Fuzzer"
BANKED_BIN="$SIMDIR/generated-src/f2/f2-fasedtests-AXI4Fuzzer-BankedLLCDRAMConfig-DefaultF1Config/VAXI4Fuzzer"

MAX_CYCLES=200000000
PASS_COUNT=0
FAIL_COUNT=0
TOTAL=0

run_test() {
    local name="$1"
    local bin="$2"
    local run_id="$3"
    local logfile="$RESULTS_DIR/${name}_run${run_id}.log"
    TOTAL=$((TOTAL + 1))

    echo -n "  [$name run $run_id] ... "
    cd "$(dirname "$bin")"

    # Run with timeout (5 min max per run)
    if timeout 300 "./$( basename "$bin")" +max-cycles=$MAX_CYCLES 2>"$logfile"; then
        # Check for PASSED in stderr log
        if grep -q "PASSED" "$logfile"; then
            cycles=$(grep "after.*cycles" "$logfile" | grep -oP '\d+(?= cycles)')
            echo "PASSED (${cycles} cycles)"
            PASS_COUNT=$((PASS_COUNT + 1))
        else
            echo "COMPLETED but no PASSED marker"
            FAIL_COUNT=$((FAIL_COUNT + 1))
        fi
    else
        exit_code=$?
        if [ $exit_code -eq 124 ]; then
            echo "TIMEOUT (exceeded 300s)"
        else
            echo "FAILED (exit code $exit_code)"
        fi
        FAIL_COUNT=$((FAIL_COUNT + 1))
        # Check for assertion failures
        if grep -qi "assert\|error\|abort" "$logfile" 2>/dev/null; then
            echo "    Assertion/error found:"
            grep -i "assert\|error\|abort" "$logfile" | head -5
        fi
    fi
    cd "$SIMDIR"
}

echo "============================================"
echo " Banked LLC Stress Test Suite"
echo " Single-bank vs 2-bank LLC"
echo " $(date)"
echo "============================================"
echo ""

# Run 5 iterations of each config
for i in 1 2 3 4 5; do
    echo "--- Iteration $i ---"
    run_test "single_bank" "$SINGLE_BIN" "$i"
    run_test "banked_2bank" "$BANKED_BIN" "$i"
    echo ""
done

echo "============================================"
echo " RESULTS: $PASS_COUNT passed, $FAIL_COUNT failed out of $TOTAL"
echo "============================================"

# Extract cycle counts for comparison
echo ""
echo "--- Cycle Count Comparison ---"
echo "Single-bank:"
for f in "$RESULTS_DIR"/single_bank_run*.log; do
    run=$(basename "$f" .log)
    cycles=$(grep "after.*cycles" "$f" 2>/dev/null | grep -oP '\d+(?= cycles)' || echo "N/A")
    echo "  $run: $cycles cycles"
done
echo "Banked (2-bank):"
for f in "$RESULTS_DIR"/banked_2bank_run*.log; do
    run=$(basename "$f" .log)
    cycles=$(grep "after.*cycles" "$f" 2>/dev/null | grep -oP '\d+(?= cycles)' || echo "N/A")
    echo "  $run: $cycles cycles"
done

# Check for any assertion failures across all runs
echo ""
echo "--- Assertion Check ---"
assertion_count=$(grep -rli "assert\|Assertion" "$RESULTS_DIR"/*.log 2>/dev/null | wc -l)
if [ "$assertion_count" -eq 0 ]; then
    echo "No assertion failures found in any run."
else
    echo "WARNING: $assertion_count log(s) contain assertion-related messages:"
    grep -rli "assert\|Assertion" "$RESULTS_DIR"/*.log 2>/dev/null
fi

exit $FAIL_COUNT
