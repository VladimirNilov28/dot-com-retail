import os

from github import GitHubClient
from config import (
    BASE_BRANCH,
    AUTO_PR_BRANCH_PREFIXES
)


def should_create_pr(
        branch
):

    return any(
        branch.startswith(prefix)
        for prefix in AUTO_PR_BRANCH_PREFIXES
    )


def create_title(
        branch
):

    return (
        branch
        .split('/', 1)[1]
        .replace('-', ' ')
        .capitalize()
    )


def create_body(
        branch
):

    return f"""
## Automated pull request

This draft PR was created automatically by Jenkins.

- **Source:** `{branch}`
- **Target:** `{BASE_BRANCH}`
- **Build:** #{os.getenv('BUILD_NUMBER')}

Jenkins will execute build validation and tests.
"""


def main():

    branch = os.environ['BRANCH_NAME']


    if branch in [
        BASE_BRANCH,
        'main'
    ]:
        print(
            'Protected branch, skipping.'
        )
        return


    if not should_create_pr(branch):
        print(
            'Branch does not require PR.'
        )
        return


    github = GitHubClient()


    existing = github.get_open_pr(
        branch,
        BASE_BRANCH
    )


    if existing:

        print(
            f"PR #{existing[0]['number']} already exists"
        )

        return


    pr = github.create_pr(
        title=create_title(branch),
        branch=branch,
        base=BASE_BRANCH,
        body=create_body(branch)
    )


    print(
        f"Created PR #{pr['number']}"
    )


if __name__ == '__main__':
    main()