pipeline {
    agent any

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