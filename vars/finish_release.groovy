pipeline {
    agent any

    parameters {
        choice(
            name: 'Project', 
            choices: ['dan05011991/barebones-react','dan05011991/demo-application-backend','vickeryw/bandpCore'], 
            description: ''
        )
        string(
            name: 'Release', 
            defaultValue: 'DEFAULT', 
            description: 'This is the release branch you wish to finish. This will merge the changes into master and develop'
        )
    }

    environment {
        SOURCE_BRANCH = "${Release}"
        INTEGRATION_BRANCH = 'develop'
        OPERATIONAL_BRANCH = 'master'
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
                            message: "Please provide approval for release to finish",
                            ok: 'Approved',
                            submitter: 'john'
                    )
                }
            }
        }

        stage('Finish Release') {
            steps {
                git(
                    branch: "${SOURCE_BRANCH}",
                    url: "git@github.com:${project}.git",
                    credentialsId: 'ssh'
                )

                script {
                    sh """
                        git checkout ${INTEGRATION_BRANCH}
                        git pull origin ${INTEGRATION_BRANCH}
                        git merge --no-ff ${SOURCE_BRANCH}
                        git push origin ${INTEGRATION_BRANCH}

                        git checkout ${OPERATIONAL_BRANCH}
                        git pull origin ${OPERATIONAL_BRANCH}
                        git merge --no-ff ${SOURCE_BRANCH}
                        git push origin ${OPERATIONAL_BRANCH} 
                    """
                }
            }
        }
    }
}
