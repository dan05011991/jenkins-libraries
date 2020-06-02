pipeline {
    agent any

    parameters {
        choice(
            name: 'Project', 
            choices: ['dan05011991/barebones-react','dan05011991/demo-application-backend','vickeryw/bandpCore'], 
            description: ''
        )
        string(
            name: 'Label', 
            defaultValue: 'DEFAULT', 
            description: 'This is unique name which will be prefixed on the end of the branch e.g. release/mylabel'
        )
    }

    environment {
        SOURCE_BRANCH = 'develop'
    }

    stages {
        stage('Clean') {
            steps {
                cleanWs()
            }
        }

        stage('Start Release') {
            steps {
                git(
                    branch: "${SOURCE_BRANCH}",
                    url: "git@github.com:${project}.git",
                    credentialsId: 'ssh'
                )

                script {
                    sh "git checkout -b release/${env.Label}"
                    sh "git push origin release/${env.Label}"
                }
            }
        }
    }
}
