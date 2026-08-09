import os
import subprocess

from .github_client import GitHubClient
from .reports import TestReport

from .config import (
    JUNIT_RESULTS_PATH,
    GITHUB_OWNER,
    GITHUB_REPO
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
        'SUCCESS': (':white_check_mark:', 'Passed', 'brightgreen'),
        'UNSTABLE': (':warning:', 'Unstable', 'yellow'),
        'FAILURE': (':x:', 'Failed', 'red')
    }


    return statuses.get(
        result,
        (':x:', result, 'red')
    )


def make_badge(
        label,
        message,
        color
):

    label_enc = label.replace(' ', '%20')
    message_enc = message.replace(' ', '%20')

    return f"![{label}](https://img.shields.io/badge/{label_enc}-{message_enc}-{color})"


def create_markdown():

    icon, status, color = get_status()


    report = TestReport(
        JUNIT_RESULTS_PATH
    )


    tests = report.collect()


    status_badge = make_badge('CI', status, color)

    tests_badge = make_badge(
        'tests',
        f"{tests['passed']}%20passed%2C%20{tests['failed']}%20failed",
        color
    )


    failed_section = ''


    if tests['failed_tests']:

        failed_items = '\n'.join(
            f"- `{t['name']}`\n  ```\n  {t['message']}\n  ```"
            for t in tests['failed_tests']
        )

        failed_section = f"""
<details>
<summary>❌ {tests['failed']} failed test(s) — click to expand</summary>

{failed_items}

</details>
"""


    commit = get_commit()

    commit_url = (
        f"https://github.com/{GITHUB_OWNER}/{GITHUB_REPO}/commit/{commit}"
    )

    build_url = os.getenv('BUILD_URL', '')

    build_number = os.getenv('BUILD_NUMBER', '?')


    build_link = (
        f"[#{build_number}]({build_url})"
        if build_url else f"#{build_number}"
    )


    return f"""
{status_badge} {tests_badge}

### {icon} Jenkins CI — {status}

| | |
|-|-|
| Commit | [`{commit}`]({commit_url}) |
| Tests | {tests['passed']} passed, {tests['failed']} failed, {tests['skipped']} skipped |
| Duration | {os.getenv('BUILD_DURATION', 'unknown')} |
| Build | {build_link} |

{failed_section}

<sub>This comment reflects the latest CI run and updates only when the result changes.</sub>
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