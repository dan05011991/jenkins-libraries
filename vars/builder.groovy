def call(Map pipelineParams) {
    pipeline {
        agent any

        stages {

            stage('Build') { // for display purposes
                steps {

                    dir('project') {

                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${env.GIT_URL}",
                            credentialsId: 'ssh'
                        )
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
                        sh 'mvn release:update-versions -B'
                        sh 'git add pom.xml'
                        sh 'git commit -m \'Automated commit: release project\''

                        sshagent(credentials: ['ssh']) {
                            sh('git push origin master')
                        }
                    }
                }
            }

            stage('Docker Build') {

                steps {

                    dir('project') {

                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${env.GIT_URL}",
                            credentialsId: 'ssh'
                        )

                        def tag = sh (
                            script: 'mvn -f $PROJECT_DIR/pom.xml -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
                            returnStdout: true
                        ).trim()

                        withDockerRegistry([ credentialsId: "2f7c1cda-f99d-415d-9cf7-e79b414112fc", url: "" ]) {
                            sh "docker build . -t ${pipelineParams.imageName}${tag}"
                            sh "docker push ${pipelineParams.imageName}${tag}"
                        }

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

                        git(
                                branch: "${pipelineParams.deploymentBranch}",
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
                    branch 'master'
                }

                steps {
                    dir('deployment') {

                        git(
                                branch: "${pipelineParams.deploymentBranch}",
                                url: "${pipelineParams.deploymentRepo}",
                                credentialsId: 'ssh'
                        )

                        sh 'docker-compose -f docker-compose.yaml up -d'
                    }
                }
            }
        }
    }
}

