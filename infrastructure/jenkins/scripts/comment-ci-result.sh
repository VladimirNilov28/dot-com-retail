#!/usr/bin/env bash

set -euo pipefail

: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${CHANGE_ID:?CHANGE_ID is required}"
: "${BUILD_NUMBER:?BUILD_NUMBER is required}"
: "${BUILD_URL:?BUILD_URL is required}"
: "${BUILD_RESULT:?BUILD_RESULT is required}"

OWNER="VladimirNilov28"
REPO="dot-com-retail"

COMMIT_SHA="${CHANGE_BRANCH_SHA:-${GIT_COMMIT:-unknown}}"
SHORT_SHA="$(printf '%s' "$COMMIT_SHA" | cut -c1-7)"

RESULT_ICON="❌"

if [[ "$BUILD_RESULT" == "SUCCESS" ]]; then
  RESULT_ICON="✅"
elif [[ "$BUILD_RESULT" == "UNSTABLE" ]]; then
  RESULT_ICON="⚠️"
fi

RESULT_DIR="backend/build/test-results/test"

TOTAL=0
FAILED=0
SKIPPED=0
PASSED=0

if compgen -G "${RESULT_DIR}/*.xml" > /dev/null; then
  TOTAL="$(
    grep -ho 'tests="[0-9]*"' "${RESULT_DIR}"/*.xml |
      grep -o '[0-9]*' |
      awk '{ total += $1 } END { print total + 0 }'
  )"

  FAILED="$(
    grep -ho 'failures="[0-9]*"' "${RESULT_DIR}"/*.xml |
      grep -o '[0-9]*' |
      awk '{ total += $1 } END { print total + 0 }'
  )"

  ERRORS="$(
    grep -ho 'errors="[0-9]*"' "${RESULT_DIR}"/*.xml |
      grep -o '[0-9]*' |
      awk '{ total += $1 } END { print total + 0 }'
  )"

  SKIPPED="$(
    grep -ho 'skipped="[0-9]*"' "${RESULT_DIR}"/*.xml |
      grep -o '[0-9]*' |
      awk '{ total += $1 } END { print total + 0 }'
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
    except ET.ParseError:
        continue

    for testcase in root.iter("testcase"):
        has_failure = (
            testcase.find("failure") is not None
            or testcase.find("error") is not None
        )

        if not has_failure:
            continue

        class_name = testcase.attrib.get("classname", "unknown")
        test_name = testcase.attrib.get("name", "unknown")
        failed.append(f"- `{class_name}.{test_name}`")

for item in failed[:20]:
    print(item)
PY
  )"
fi

LOG_EXCERPT=""

if [[ "$BUILD_RESULT" != "SUCCESS" ]] && [[ -f backend/gradle-test.log ]]; then
  LOG_EXCERPT="$(
    tail -n 80 backend/gradle-test.log |
      sed 's/```/` ` `/g'
  )"
fi

COMMENT_FILE="$(mktemp)"
PAYLOAD_FILE="$(mktemp)"

cat > "$COMMENT_FILE" <<EOF
## ${RESULT_ICON} Jenkins CI build #${BUILD_NUMBER}

| Field | Value |
|---|---|
| Result | **${BUILD_RESULT}** |
| Commit | \`${SHORT_SHA}\` |
| Tests | ${PASSED} passed, ${FAILED} failed, ${SKIPPED} skipped |
| Jenkins | [Open build #${BUILD_NUMBER}](${BUILD_URL}) |
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
  '{body: $body}' > "$PAYLOAD_FILE"

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

echo "Published CI result to PR #${CHANGE_ID}."