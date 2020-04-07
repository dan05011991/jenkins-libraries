def call(Map pipelineParams) {
    pipeline {
        agent any

        stages {

            stage('Build') { // for display purposes
                steps {
                    // Get some code from a GitHub repository
                    git(
                        branch: 'master',
                        url: 'https://github.com/dan05011991/demo-application.git',
                        credentialsId: '2f7c1cda-f99d-415d-9cf7-e79b414112fc'
                    )
                }

            }

            stage('Maven Build') {
                when {
                    expression {
                        pipelineParams.buildType == 'maven'
                    }
                }

                steps {
                    sh 'docker-compose -f docker/docker-compose.yaml up -d'
                }
            }


            stage('Deploy to Ref') {
                agent {
                    label 'ref'
                }

                when {
                    branch 'develop'
                }

                steps {
                    sh 'docker-compose -f docker/docker-compose.yaml up -d'
                }
            }


            stage('Deploy to Ops') {
                agent {
                    label 'ops'
                }

                when {
                    branch 'master'
                }

                steps {
                    sh 'docker-compose -f docker/docker-compose.yaml up -d'
                }
            }
        }
    }
}

