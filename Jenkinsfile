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


        stage('Test Gitea access') {
            steps {
                sshagent(credentials: ['gitea-ssh']) {
                    sh '''
                        git ls-remote \
                            git@gitea.kood.tech:vladimirnilov/i-love-shopping1.git
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