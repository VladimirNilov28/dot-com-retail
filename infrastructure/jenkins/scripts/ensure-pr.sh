#!/usr/bin/env bash

set -euo pipefail

: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${BRANCH_NAME:?BRANCH_NAME is required}"

OWNER="VladimirNilov28"
REPO="dot-com-retail"
BASE_BRANCH="dev"

SOURCE_BRANCH="${BRANCH_NAME}"

case "$SOURCE_BRANCH" in
  feature/*|fix/*|ci/*)
    ;;
  *)
    echo "Branch '${SOURCE_BRANCH}' does not require an automatic PR."
    exit 0
    ;;
esac

if [[ "$SOURCE_BRANCH" == "$BASE_BRANCH" || "$SOURCE_BRANCH" == "main" ]]; then
  echo "Protected branch '${SOURCE_BRANCH}' must not create an automatic PR."
  exit 0
fi

echo "Checking whether an open PR already exists:"
echo "  head=${OWNER}:${SOURCE_BRANCH}"
echo "  base=${BASE_BRANCH}"

QUERY_URL="$(
  printf \
    'https://api.github.com/repos/%s/%s/pulls?state=open&head=%s%%3A%s&base=%s' \
    "$OWNER" \
    "$REPO" \
    "$OWNER" \
    "$SOURCE_BRANCH" \
    "$BASE_BRANCH"
)"

EXISTING_PR_RESPONSE="$(
  curl \
    --fail \
    --silent \
    --show-error \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2026-03-10" \
    "$QUERY_URL"
)"

EXISTING_PR_NUMBER="$(
  printf '%s' "$EXISTING_PR_RESPONSE" |
    jq -r '.[0].number // empty'
)"

if [[ -n "$EXISTING_PR_NUMBER" ]]; then
  echo "Open PR #${EXISTING_PR_NUMBER} already exists."
  exit 0
fi

PR_TITLE="$(
  printf '%s' "$SOURCE_BRANCH" |
    sed 's#^[^/]*/##' |
    tr '-' ' '
)"

PR_BODY="$(cat <<EOF
## Automated pull request

This draft PR was created automatically by Jenkins.

- **Source:** \`${SOURCE_BRANCH}\`
- **Target:** \`${BASE_BRANCH}\`
- **Jenkins build:** [#${BUILD_NUMBER}](${BUILD_URL})

Jenkins will run the project build, backend tests and CI analysis for every update to this branch.
EOF
)"

REQUEST_FILE="$(mktemp)"

jq -n \
  --arg title "$PR_TITLE" \
  --arg head "${OWNER}:${SOURCE_BRANCH}" \
  --arg base "$BASE_BRANCH" \
  --arg body "$PR_BODY" \
  '{
    title: $title,
    head: $head,
    base: $base,
    body: $body,
    draft: true
  }' > "$REQUEST_FILE"

echo "Creating draft PR from '${SOURCE_BRANCH}' to '${BASE_BRANCH}'."

CREATE_RESPONSE="$(
  curl \
    --fail \
    --silent \
    --show-error \
    --request POST \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2026-03-10" \
    "https://api.github.com/repos/${OWNER}/${REPO}/pulls" \
    --data @"$REQUEST_FILE"
)"

rm -f "$REQUEST_FILE"

CREATED_NUMBER="$(
  printf '%s' "$CREATE_RESPONSE" |
    jq -r '.number'
)"

CREATED_URL="$(
  printf '%s' "$CREATE_RESPONSE" |
    jq -r '.html_url'
)"

echo "Created draft PR #${CREATED_NUMBER}: ${CREATED_URL}"