pipeline {
    agent any

    parameters {
        string(
            name: 'Label', 
            defaultValue: 'DEFAULT', 
            description: 'This is unique name which will be prefixed on the end of the branch e.g. release/release-mylabel'
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

        stage('Obtain approval') {
            steps {
                timeout(time: 5, unit: 'MINUTES') { 
                    input(
                            message: "Please provide approval for release to start",
                            ok: 'Approved',
                            submitter: 'john'
                    )
                }
            }
        }

        stage('Start Release') {
            steps {
                git(
                    branch: "${env.Branch}",
                    url: "git@github.com:dan05011991/demo-application-backend.git",
                    credentialsId: 'ssh'
                )

                script {
                    sh "git checkout -b release/release-${env.Label}"
                    sh "git push origin release/release-${env.Label}"
                }
            }
        }
    }
}
