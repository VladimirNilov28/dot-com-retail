import os

from dotenv import load_dotenv


load_dotenv(
    'infrastructure/jenkins/.env'
)


GITHUB_TOKEN = os.getenv(
    'GITHUB_TOKEN'
)

GITHUB_OWNER = os.getenv(
    'GITHUB_OWNER',
    'VladimirNilov28'
)

GITHUB_REPO = os.getenv(
    'GITHUB_REPO',
    'dot-com-retail'
)


BASE_BRANCH = os.getenv(
    'DEFAULT_BASE_BRANCH',
    'dev'
)


AUTO_PR_BRANCH_PREFIXES = [
    prefix.strip()
    for prefix in os.getenv(
        'AUTO_PR_BRANCH_PREFIXES',
        'feature/,fix/,ci/'
    ).split(',')
]


JUNIT_RESULTS_PATH = os.getenv(
    'JUNIT_RESULTS_PATH',
    'backend/build/test-results/test'
)


GRADLE_LOG_PATH = os.getenv(
    'GRADLE_LOG_PATH',
    'backend/gradle-test.log'
)


DEEPSEEK_API_KEY = os.getenv(
    'DEEPSEEK_API_KEY'
)


DEEPSEEK_MODEL = os.getenv(
    'DEEPSEEK_MODEL',
    'deepseek-chat'
)