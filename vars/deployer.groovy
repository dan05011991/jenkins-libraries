pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
    }

    stages {

        stage('Checkout deployment') {
            steps {

                dir('project') {

                    git(
                            branch: "${env.SOURCE_BRANCH}",
                            url: "${env.SOURCE_URL}",
                            credentialsId: 'ssh'
                    )
                }
            }
        }

        stage('Deploy') {
            parallel {
                stage('Deploy to Ref') {
                    agent {
                        label 'ref'
                    }

                    when {
                        expression {
                            isRefBuild()
                        }
                    }

                    steps {
                        dir('deployment') {

                            git(
                                    branch: "${SOURCE_BRANCH}",
                                    url: "${pipelineParams.deploymentRepo}",
                                    credentialsId: 'ssh'
                            )

                            sh 'docker-compose -f docker-compose.yaml up -d'
                        }
                    }
                }


                stage('Deploy to Ops') {
                    agent {
                        label 'ops'
                    }

                    when {
                        expression {
                            isOpsBuild()
                        }
                    }

                    steps {
                        dir('deployment') {

                            build job: 'Deploy', parameters: [
                                    [$class: 'StringParameterValue', name: 'environment', value: "ops"],
                                    [$class: 'StringParameterValue', name: 'repo', value: "${pipelineParams.deploymentRepo}"],
                                    [$class: 'StringParameterValue', name: 'branch', value: "${SOURCE_BRANCH}"]
                            ]
                        }
                    }
                }
            }
        }
    }
}


