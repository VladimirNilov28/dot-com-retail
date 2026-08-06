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


        stage('Backend tests') {

            steps {

                dir('backend') {

                    sh '''
                        ./gradlew test
                    '''

                }

            }

        }

        stage('Comment PR') {
            steps {
                withCredentials([
                    string(
                        credentialsId: 'github-token',
                        variable: 'GITHUB_TOKEN'
                    )
                ]) {
                    sh '''
                        set -e

                        curl --fail --silent --show-error \
                            --request POST \
                            -H "Authorization: Bearer $GITHUB_TOKEN" \
                            -H "Accept: application/vnd.github+json" \
                            -H "X-GitHub-Api-Version: 2022-11-28" \
                            https://api.github.com/repos/VladimirNilov28/dot-com-retail/issues/1/comments \
                            --data '{"body":"🤖 Jenkins CI finished successfully"}'
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