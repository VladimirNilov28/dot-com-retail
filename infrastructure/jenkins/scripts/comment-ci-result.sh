#!/usr/bin/env bash

set -euo pipefail

: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${CHANGE_ID:?CHANGE_ID is required}"
: "${BUILD_NUMBER:?BUILD_NUMBER is required}"
: "${BUILD_URL:?BUILD_URL is required}"
: "${BUILD_RESULT:?BUILD_RESULT is required}"

OWNER="VladimirNilov28"
REPO="dot-com-retail"

SHORT_SHA="$(
    git rev-parse --short HEAD 2>/dev/null || echo "unknown"
)"

RESULT_DIR="backend/build/test-results/test"

case "$BUILD_RESULT" in
    SUCCESS)
        RESULT_ICON="✅"
        RESULT_TEXT="Passed"
        ;;
    UNSTABLE)
        RESULT_ICON="⚠️"
        RESULT_TEXT="Unstable"
        ;;
    *)
        RESULT_ICON="❌"
        RESULT_TEXT="Failed"
        ;;
esac


TOTAL=0
FAILED=0
SKIPPED=0
PASSED=0


if compgen -G "${RESULT_DIR}/*.xml" > /dev/null; then

    TOTAL="$(
        grep -ho 'tests="[0-9]*"' "${RESULT_DIR}"/*.xml |
        grep -o '[0-9]*' |
        awk '{sum += $1} END {print sum+0}'
    )"


    FAILED="$(
        grep -ho 'failures="[0-9]*"' "${RESULT_DIR}"/*.xml |
        grep -o '[0-9]*' |
        awk '{sum += $1} END {print sum+0}'
    )"


    ERRORS="$(
        grep -ho 'errors="[0-9]*"' "${RESULT_DIR}"/*.xml |
        grep -o '[0-9]*' |
        awk '{sum += $1} END {print sum+0}'
    )"


    SKIPPED="$(
        grep -ho 'skipped="[0-9]*"' "${RESULT_DIR}"/*.xml |
        grep -o '[0-9]*' |
        awk '{sum += $1} END {print sum+0}'
    )"


    FAILED=$((FAILED + ERRORS))
    PASSED=$((TOTAL - FAILED - SKIPPED))
fi


FAILED_TESTS=""

if compgen -G "${RESULT_DIR}/*.xml" > /dev/null; then

FAILED_TESTS="$(
python3 - "${RESULT_DIR}" <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET

directory = sys.argv[1]

failed = []

for path in glob.glob(os.path.join(directory, "*.xml")):

    try:
        root = ET.parse(path).getroot()
    except Exception:
        continue

    for test in root.iter("testcase"):

        if (
            test.find("failure") is None
            and test.find("error") is None
        ):
            continue

        classname = test.attrib.get(
            "classname",
            "unknown"
        )

        name = test.attrib.get(
            "name",
            "unknown"
        )

        failed.append(
            f"- `{classname}.{name}`"
        )


for item in failed[:20]:
    print(item)

PY
)"

fi


LOG_EXCERPT=""

if [[ "$BUILD_RESULT" != "SUCCESS" ]] &&
   [[ -f backend/gradle-test.log ]]; then

LOG_EXCERPT="$(
    tail -n 80 backend/gradle-test.log |
    sed 's/```/` ` `/g'
)"

fi


COMMENT_FILE="$(mktemp)"
PAYLOAD_FILE="$(mktemp)"


cat > "$COMMENT_FILE" <<EOF
## ${RESULT_ICON} Jenkins CI ${RESULT_TEXT}

Validation completed automatically.

### Build summary

| | |
|-|-|
| Status | ${RESULT_ICON} ${RESULT_TEXT} |
| Commit | \`${SHORT_SHA}\` |
| Tests | ${PASSED} passed, ${FAILED} failed, ${SKIPPED} skipped |
| Duration | ${BUILD_DURATION:-unknown} |
| Build | [#${BUILD_NUMBER}](${BUILD_URL}) |

EOF


if [[ -n "$FAILED_TESTS" ]]; then

cat >> "$COMMENT_FILE" <<EOF

### Failed tests

${FAILED_TESTS}

EOF

fi


if [[ -n "$LOG_EXCERPT" ]]; then

cat >> "$COMMENT_FILE" <<EOF

<details>
<summary>Failure log excerpt</summary>

\`\`\`text
${LOG_EXCERPT}
\`\`\`

</details>

EOF

fi


cat >> "$COMMENT_FILE" <<EOF

_This comment represents one CI execution and is kept as part of the PR history._
EOF


jq -n \
    --rawfile body "$COMMENT_FILE" \
    '{body: $body}' \
    > "$PAYLOAD_FILE"



curl \
    --fail \
    --silent \
    --show-error \
    --request POST \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2026-03-10" \
    "https://api.github.com/repos/${OWNER}/${REPO}/issues/${CHANGE_ID}/comments" \
    --data @"$PAYLOAD_FILE"



rm -f "$COMMENT_FILE" "$PAYLOAD_FILE"


echo "Published CI result to PR #${CHANGE_ID}"