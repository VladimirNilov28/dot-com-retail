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


        // Новая стадия: определяем, менялся ли backend/ с прошлого раза.
        // Логика:
        // - если есть GIT_PREVIOUS_SUCCESSFUL_COMMIT (эта же джоба уже успешно
        //   собиралась раньше) -> сравниваем HEAD с ним. Это ровно то, что
        //   просил: "не гонять тесты повторно, если код с прошлого пуша не менялся".
        // - если такой базы нет (первая сборка этой ветки/PR) -> считаем,
        //   что backend поменялся, и гоняем всё. Лучше лишний раз прогнать
        //   тесты, чем пропустить их по ошибке.
        stage('Detect changes') {
            steps {
                script {

                    def baseRef = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT

                    if (!baseRef) {

                        echo 'Нет предыдущей успешной сборки для сравнения — гоняем всё.'

                        env.BACKEND_CHANGED = 'true'

                    } else {

                        def changedFiles = sh(
                            script: "git diff --name-only ${baseRef} HEAD",
                            returnStdout: true
                        ).trim()

                        echo "Изменённые файлы с последней успешной сборки:\n${changedFiles}"

                        def touched = changedFiles
                            .split('\n')
                            .any { it.startsWith('backend/') }

                        env.BACKEND_CHANGED = touched.toString()
                    }

                    echo "BACKEND_CHANGED=${env.BACKEND_CHANGED}"
                }
            }
        }


        stage('Mirror to Gitea') {

            steps {

                script {
                    env.MIRROR_BRANCH = env.CHANGE_BRANCH ?: env.BRANCH_NAME
                }

                sshagent(credentials: ['gitea-ssh']) {

                    sh '''
                        set -euo pipefail

                        echo "Mirroring branch '${MIRROR_BRANCH}' to Gitea"

                        git remote remove gitea 2>/dev/null || true

                        git remote add gitea \
                            git@gitea.kood.tech:vladimirnilov/i-love-shopping1.git

                        git push \
                            --force \
                            gitea \
                            "HEAD:refs/heads/${MIRROR_BRANCH}"

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
                        python infrastructure/jenkins/run.py ensure-pr
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


        // Скипаются, если backend не менялся
        stage('Backend build') {

            when {
                expression { env.BACKEND_CHANGED == 'true' }
            }

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

            when {
                expression { env.BACKEND_CHANGED == 'true' }
            }

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

                if (env.CHANGE_ID && env.BACKEND_CHANGED == 'true') {

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
                                python infrastructure/jenkins/run.py comment-ci
                            '''
                        }
                    }

                } else if (env.CHANGE_ID) {

                    echo 'Backend не менялся — комментарий в PR не нужен.'

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