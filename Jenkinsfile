pipeline {

    agent any

    options {
        skipDefaultCheckout(true)
        timestamps()
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build context') {
            steps {
                sh '''
                    echo "BRANCH_NAME=${BRANCH_NAME:-}"
                    echo "CHANGE_ID=${CHANGE_ID:-}"
                    echo "CHANGE_BRANCH=${CHANGE_BRANCH:-}"
                    echo "CHANGE_TARGET=${CHANGE_TARGET:-}"
                    echo "GIT_COMMIT=${GIT_COMMIT:-}"
                    echo "BUILD_NUMBER=${BUILD_NUMBER:-}"
                '''
            }
        }

        stage('Mirror to Gitea') {
            when {
                not {
                    changeRequest()
                }
            }

            steps {
                sshagent(credentials: ['gitea-ssh']) {
                    sh '''
                        set -euo pipefail

                        echo "Mirroring branch '${BRANCH_NAME}' to Gitea"

                        git remote remove gitea 2>/dev/null || true

                        git remote add gitea \
                            git@gitea.kood.tech:vladimirnilov/i-love-shopping1.git

                        git push \
                            --force \
                            gitea \
                            "HEAD:refs/heads/${BRANCH_NAME}"

                        echo "Mirror completed"
                    '''
                }
            }
        }

        stage('Ensure pull request') {
            when {
                allOf {
                    not {
                        changeRequest()
                    }

                    anyOf {
                        branch pattern: 'feature/.*', comparator: 'REGEXP'
                        branch pattern: 'fix/.*', comparator: 'REGEXP'
                        branch pattern: 'ci/.*', comparator: 'REGEXP'
                    }
                }
            }

            steps {
                withCredentials([
                    string(
                        credentialsId: 'github-token',
                        variable: 'GITHUB_TOKEN'
                    )
                ]) {
                    sh '''
                        chmod +x infrastructure/jenkins/scripts/ensure-pr.sh
                        infrastructure/jenkins/scripts/ensure-pr.sh
                    '''
                }
            }
        }

        stage('Verify') {
            steps {
                sh '''
                    echo "Project structure"

                    test -d backend
                    test -d frontend
                    test -d infrastructure
                    test -f backend/gradlew
                '''
            }
        }

        stage('Backend build') {
            steps {
                dir('backend') {
                    sh '''
                        chmod +x gradlew
                        ./gradlew --no-daemon classes
                    '''
                }
            }
        }

        stage('Backend tests') {
            steps {
                dir('backend') {
                    sh '''
                        set -o pipefail

                        chmod +x gradlew
                        ./gradlew test 2>&1 | tee gradle-test.log
                    '''
                }
            }
        }
    }

    post {
        always {

            archiveArtifacts(
                allowEmptyArchive: true,
                artifacts: '''
                    backend/gradle-test.log,
                    backend/build/reports/**
                ''',
                fingerprint: true
            )

            junit(
                allowEmptyResults: true,
                testResults: 'backend/build/test-results/test/*.xml'
            )

            script {
                if (env.CHANGE_ID) {
                    withCredentials([
                        string(
                            credentialsId: 'github-token',
                            variable: 'GITHUB_TOKEN'
                        )
                    ]) {
                        withEnv([
                            "BUILD_RESULT=${currentBuild.currentResult}",
                            "BUILD_DURATION=${currentBuild.durationString}"
                        ]) {
                            sh '''
                                chmod +x infrastructure/jenkins/scripts/comment-ci-result.sh
                                infrastructure/jenkins/scripts/comment-ci-result.sh
                            '''
                        }
                    }
                } else {
                    echo 'This is not a PR build; skipping CI comment.'
                }
            }
        }

        success {
            echo 'Build successful'
        }

        failure {
            echo 'Build failed'
        }
    }
}