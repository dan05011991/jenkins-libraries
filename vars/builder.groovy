def call(Map pipelineParams) {
    pipeline {
        agent any
//
//        options {
//            skipDefaultCheckout()
//        }

        stages {

            stage('Build') { // for display purposes
                steps {

                    dir('project') {

                        sh 'pwd; ls'

                        // Get some code from a GitHub repository
                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${env.GIT_URL}",
                            credentialsId: 'ssh'
                        )

                        sh 'pwd; ls'
                    }
                }

            }

            stage('Maven Build') {
                when {
                    expression {
                        pipelineParams.buildType == 'maven'
                    }
                }

                steps {

                    dir('project') {
                        sh 'pwd; ls'

                        sh 'mvn release:update-versions -B'
                        sh 'git add pom.xml'
                        sh 'git commit -m \'Automated commit: release project\''

                        sshagent(credentials: ['ssh']) {
                            sh('git push origin master')
                        }

                        sh 'pwd; ls'
                        //sh 'mvn clean build -Ddocker'
                    }
                }
            }

            stage('Update deployment file') {
                steps {

                    dir('deployment') {

                        git(
                                branch: "${pipelineParams.deploymentBranch}",
                                url: "${pipelineParams.deploymentRepo}",
                                credentialsId: 'ssh'
                        )

                        sh 'pwd; ls'




                        sh 'pwd; ls'

                        sh "./update.sh ${pipelineParams.imageName} docker-compose.yaml .."

                        sshagent(credentials: ['ssh']) {
                            sh('git add docker-compose.yaml')
                            sh('git commit -m \'New release\'')
                            sh('git push origin master')

                        }
                    }
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
                    dir('deployment') {
                        sh 'docker-compose -f docker-compose.yaml up -d'
                    }
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
                    dir('deployment') {
                        sh 'docker-compose -f docker-compose.yaml up -d'
                    }
                }
            }
        }
    }
}

