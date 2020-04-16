pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
    }

    stages {
        parallel {
            stage('Ref') {

                agent {
                    label "ref"
                }

                steps {
                    build job: 'Deploy', parameters: [
                            [$class: 'StringParameterValue', name: 'environment', value: "ops"],
                            [$class: 'StringParameterValue', name: 'repo', value: "git@github.com:dan05011991/demo-application-docker.git"],
                            [$class: 'StringParameterValue', name: 'branch', value: "develop"]
                    ]
                }
            }

            stage('Ops') {

                agent {
                    label "ops"
                }

                steps {
                    build job: 'Deploy', parameters: [
                            [$class: 'StringParameterValue', name: 'environment', value: "ops"],
                            [$class: 'StringParameterValue', name: 'repo', value: "git@github.com:dan05011991/demo-application-docker.git"],
                            [$class: 'StringParameterValue', name: 'branch', value: "master"]
                    ]
                }
            }
        }
    }
}


