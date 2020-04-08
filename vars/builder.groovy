def call(Map pipelineParams) {
    pipeline {
        agent any

        stages {

            stage('Build') { // for display purposes
                steps {

                    dir('project') {

                        sh 'pwd; ls'

                        // Get some code from a GitHub repository
                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${env.GIT_URL}",
                            credentialsId: '2f7c1cda-f99d-415d-9cf7-e79b414112fc'
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

                        sshagent(credentials: ['ssh-github']) {
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
                                credentialsId: '2f7c1cda-f99d-415d-9cf7-e79b414112fc'
                        )

                        sh 'pwd; ls'




                        sh 'pwd; ls'

                        sh "./update.sh ${pipelineParams.imageName} docker-compose.yaml .."

                        sshagent(credentials: ['ssh']) {
                            sh('git add docker-compose.yaml')
                            sh('git commit -m \'docker-compose.yaml\'')
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

