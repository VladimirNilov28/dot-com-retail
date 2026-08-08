import sys


def main():

    if len(sys.argv) < 2:
        print(
            'Usage: python run.py <command>'
        )
        sys.exit(1)


    command = sys.argv[1]


    if command == 'ensure-pr':

        from scripts.ensure_pr import main as ensure_pr

        ensure_pr()


    elif command == 'comment-ci':

        from scripts.comment_ci_result import main as comment_ci

        comment_ci()


    else:

        print(
            f'Unknown command: {command}'
        )

        sys.exit(1)


if __name__ == '__main__':
    main()