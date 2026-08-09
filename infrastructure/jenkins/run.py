import os
import sys


CURRENT_DIR = os.path.dirname(
    os.path.abspath(__file__)
)

sys.path.insert(
    0,
    CURRENT_DIR
)


def main():

    if len(sys.argv) < 2:
        print(
            'Usage: python run.py <command>'
        )
        sys.exit(1)


    command = sys.argv[1]


    if command == 'ensure-pr':

        from scripts.ensure_pr import main

        main()


    elif command == 'comment-ci':

        from scripts.comment_ci_result import main

        main()


    else:

        print(
            f'Unknown command: {command}'
        )

        sys.exit(1)


if __name__ == '__main__':
    main()