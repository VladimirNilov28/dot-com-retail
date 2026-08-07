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
                    echo "BUILD_NUMBER=${BUILD_NUMBER:-}"
                '''
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

                    ls -la

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
                        ./gradlew --no-daemon test
                    '''
                }
            }
        }
    }

    post {

        always {

            junit(
                allowEmptyResults: true,
                testResults: 'backend/build/test-results/test/*.xml'
            )

            archiveArtifacts(
                artifacts: 'backend/build/reports/**',
                allowEmptyArchive: true,
                fingerprint: true
            )
        }

        success {
            echo 'Build successful'
        }

        failure {
            echo 'Build failed'
        }
    }
}