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

    stage('Clean') {
        cleanWs()
    }

    stage('Obtain approval') {
        timeout(time: 5, unit: 'MINUTES') { 
            input(
                    message: "Please provide approval for release to start",
                    ok: 'Approved',
                    submitter: 'john'
            )
        }
    }

    stage('Start Release') {
        git(
            branch: "${env.Branch}",
            url: "git@github.com:dan05011991/demo-application-backend.git",
            credentialsId: 'ssh'
        )

        sh "git checkout -b release/release-${env.Label}"
        sh "git push origin release/release-${env.Label}"
    }
}
