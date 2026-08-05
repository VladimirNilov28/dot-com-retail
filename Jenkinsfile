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

        stage('Backend test') {
            steps {
                dir('backend') {
                    sh '''
                      chmod +x gradlew
                      ./gradlew test
                    '''
                }
            }
        }
    }

    post {

        success {
            echo "Build succesful"
        }

        failure {
            echo "Build failed"
        }
    }
}