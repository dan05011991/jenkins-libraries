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
                            credentialsId: 'ssh-github'
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
                                credentialsId: 'ssh-github'
                        )

                        sh 'pwd; ls'




                        sh 'pwd; ls'

                        sh "./update.sh ${pipelineParams.imageName} docker-compose.yaml .."
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

