pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
    }

    stages {

        stage('Deploy') {
            agent {
                label "${env.environment}"
            }

            steps {

                dir('deployment') {

                    git(
                            branch: "${env.branch}",
                            url: "${env.repo}",
                            credentialsId: 'ssh'
                    )

                    sh 'docker-compose -f docker-compose.yaml up -d'
                }
            }
        }
    }
}


