import os
import subprocess

from github_client import GitHubClient
from reports import TestReport

from config import (
    JUNIT_RESULTS_PATH
)


def get_commit():

    try:

        return subprocess.check_output(
            [
                'git',
                'rev-parse',
                '--short',
                'HEAD'
            ],
            text=True
        ).strip()

    except Exception:

        return 'unknown'


def get_status():

    result = os.getenv(
        'BUILD_RESULT',
        'FAILURE'
    )


    statuses = {
        'SUCCESS': (':white_check_mark:', 'Passed'),
        'UNSTABLE': (':warning:', 'Unstable'),
        'FAILURE': (':x:', 'Failed')
    }


    return statuses.get(
        result,
        (':x:', result)
    )


def create_markdown():

    icon, status = get_status()


    report = TestReport(
        JUNIT_RESULTS_PATH
    )


    tests = report.collect()


    failed = ''


    if tests['failed_tests']:

        failed = f"""
### Failed tests

{chr(10).join(tests['failed_tests'])}
"""


    return f"""
## {icon} Jenkins CI {status}

Validation completed automatically.

### Build summary

| | |
|-|-|
| Status | {icon} {status} |
| Commit | `{get_commit()}` |
| Tests | {tests['passed']} passed, {tests['failed']} failed, {tests['skipped']} skipped |
| Duration | {os.getenv('BUILD_DURATION', 'unknown')} |
| Build | [#{os.getenv('BUILD_NUMBER')}]({os.getenv('BUILD_URL')}) |

{failed}

_This comment represents one CI execution and is kept as part of the PR history._
"""


def main():

    change_id = os.getenv(
        'CHANGE_ID'
    )


    if not change_id:
        print(
            'Not a PR build'
        )
        return


    client = GitHubClient()


    client.create_pr_comment(
        int(change_id),
        create_markdown()
    )


if __name__ == '__main__':
    main()