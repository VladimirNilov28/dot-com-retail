pipeline {

    agent any


    options {

        skipDefaultCheckout(true)

    }


    stages {


        stage('Checkout') {

            steps {

                checkout scm

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
                '''

            }

        }


        stage('Backend build') {

            steps {

                dir('backend') {

                    sh '''
                        chmod +x gradlew
                        ./gradlew classes
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
                        branch pattern: 'feature/.*', comparator: 'REGEX'
                        branch pattern: 'fix/.*', comparator: 'REGEX'
                        branch pattern: 'ci/.*', comparator: 'REGEX'
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
                        infrastructure/jenkins/scripts/ensure-pr.sh
                    '''
                }
            }
        }

    }


    post {


        always {

            junit 'backend/build/test-results/test/*.xml'

            archiveArtifacts artifacts: 'backend/build/reports/**',
            fingerprint: true

        }


        success {

            echo "Build successful"

        }


        failure {

            echo "Build failed"

        }


    }

}