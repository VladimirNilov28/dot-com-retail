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


        stage('Build context') {
            steps {
                sh '''
                    echo "BRANCH_NAME=${BRANCH_NAME:-}"
                    echo "CHANGE_ID=${CHANGE_ID:-}"
                    echo "CHANGE_BRANCH=${CHANGE_BRANCH:-}"
                    echo "CHANGE_TARGET=${CHANGE_TARGET:-}"
                    echo "GIT_COMMIT=${GIT_COMMIT:-}"
                    echo "BUILD_NUMBER=${BUILD_NUMBER:-}"
                    echo "BUILD_URL=${BUILD_URL:-}"
                '''
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