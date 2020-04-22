pipeline {
    agent any

    parameters {
        string(
            name: 'Release Branch', 
            defaultValue: 'DEFAULT', 
            description: 'This is the branch you wish to finish. This will merge the changes into master and develop'
        )
    }

    environment {
        SOURCE_BRANCH = "${Release_Branch}"
        INTEGRATION_BRANCH = 'develop'
        OPERATIONAL_BRANCH = 'master'
    }

    stage('Clean') {
        cleanWs()
    }

    stage('Obtain approval') {
        timeout(time: 5, unit: 'MINUTES') { 
            input(
                    message: "Please provide approval for release to finish",
                    ok: 'Approved',
                    submitter: 'john'
            )
        }
    }

    stage('Finish Release') {
        git(
            branch: "${SOURCE_BRANCH}",
            url: "git@github.com:dan05011991/demo-application-backend.git",
            credentialsId: 'ssh'
        )

        sh """
            git checkout ${INTEGRATION_BRANCH}
            git pull origin ${INTEGRATION_BRANCH}
            git merge release/release-${SOURCE_BRANCH}
            git push origin ${INTEGRATION_BRANCH}

            git checkout ${OPERATIONAL_BRANCH}
            git pull origin ${OPERATIONAL_BRANCH}
            git merge release/release-${SOURCE_BRANCH}
            git push origin ${OPERATIONAL_BRANCH} 
        """
    }
}