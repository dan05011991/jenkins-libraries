pipeline {
    agent any

    parameters {
        string(
            name: 'Label', 
            defaultValue: 'DEFAULT', 
            description: 'This is unique name which will be prefixed on the end of the branch e.g. hotfix/mylabel'
        )
    }

    environment {
        SOURCE_BRANCH = 'master'
    }

    stages {
        stage('Clean') {
            steps {
                cleanWs()
            }
        }

        stage('Start Hotfix') {
            steps {
                git(
                    branch: "${SOURCE_BRANCH}",
                    url: "git@github.com:dan05011991/demo-application-backend.git",
                    credentialsId: 'ssh'
                )

                script {
                    sh "git checkout -b hotfix/${env.Label}"
                    sh "git push origin hotfix/${env.Label}"
                }
            }
        }
    }
}
