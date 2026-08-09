import requests

from ..config import (
    GITHUB_TOKEN,
    GITHUB_OWNER,
    GITHUB_REPO
)


class GitHubClient:

    def __init__(self):

        self.owner = GITHUB_OWNER
        self.repo = GITHUB_REPO

        self.base_url = (
            f'https://api.github.com/repos/'
            f'{self.owner}/{self.repo}'
        )

        self.headers = {
            'Authorization': f'Bearer {GITHUB_TOKEN}',
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28'
        }


    def _request(
            self,
            method,
            url,
            **kwargs
    ):

        response = requests.request(
            method,
            url,
            headers=self.headers,
            timeout=30,
            **kwargs
        )

        if not response.ok:
            print(response.text)

        response.raise_for_status()

        return response.json()


    def get_open_pr(
            self,
            branch,
            base
    ):

        return self._request(
            'GET',
            f'{self.base_url}/pulls',
            params={
                'state': 'open',
                'head': f'{self.owner}:{branch}',
                'base': base
            }
        )


    def create_pr(
            self,
            title,
            branch,
            base,
            body
    ):

        return self._request(
            'POST',
            f'{self.base_url}/pulls',
            json={
                'title': title,
                'head': f'{self.owner}:{branch}',
                'base': base,
                'body': body,
                'draft': True
            }
        )


    def create_pr_comment(
            self,
            pr_number,
            body
    ):

        return self._request(
            'POST',
            f'{self.base_url}/issues/{pr_number}/comments',
            json={
                'body': body
            }
        )

    def list_pr_comments(
            self,
            pr_number
    ):

        return self._request(
            'GET',
            f'{self.base_url}/issues/{pr_number}/comments'
        )


    def update_pr_comment(
            self,
            comment_id,
            body
    ):

        return self._request(
            'PATCH',
            f'{self.base_url}/issues/comments/{comment_id}',
            json={
                'body': body
            }
        )