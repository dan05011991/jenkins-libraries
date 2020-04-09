def call(Map pipelineParams) {
    pipeline {
        agent any

        stages {

            stage('Checkout') {

                when {
                    branch 'develop'
                }


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

            stage('Maven version update') {

                when {
                    expression {
                        pipelineParams.buildType == 'maven' && env.GIT_BRANCH == 'develop'
                    }
                }

                steps {

                    dir('project') {
                        sh 'mvn release:update-versions -B'
                        sh 'git add pom.xml'
                        sh 'git commit -m \'Automated commit: release project\''

                        sshagent(credentials: ['ssh']) {
                            sh("git push origin ${env.GIT_BRANCH}")
                        }
                    }
                }
            }

            stage('Compose deployment update') {
                steps {

                    dir('project') {

                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${env.GIT_URL}",
                            credentialsId: 'ssh'
                        )
                    }

                    dir('deployment') {

                        git(
                                branch: "${env.GIT_BRANCH}",
                                url: "${pipelineParams.deploymentRepo}",
                                credentialsId: 'ssh'
                        )

                        sshagent(credentials: ['ssh']) {

                            sh "./update.sh ${pipelineParams.imageName} docker-compose.yaml ../project ${env.GIT_BRANCH}"
                        }
                    }
                }
            }

            stage('Docker build and tag') {

                when {
                    branch 'develop'
                }

                steps {

                    dir('project') {

                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${env.GIT_URL}",
                            credentialsId: 'ssh'
                        )

                        script {
                            def tag = sh(
                                    script: 'mvn -f pom.xml -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
                                    returnStdout: true
                            ).trim()

                            withDockerRegistry([ credentialsId: "dockerhub", url: "" ]) {
                                sh "docker build . -t ${pipelineParams.imageName}${tag}"
                                sh "docker push ${pipelineParams.imageName}${tag}"
                            }
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
                                branch: "${env.GIT_BRANCH}",
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
                                branch: "${env.GIT_BRANCH}",
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

