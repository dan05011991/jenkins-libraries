
def lastCommitIsBumpCommit() {
    lastCommit = sh([script: 'git log -1', returnStdout: true])
    if (lastCommit.contains("[Automated commit: version bump]")) {
        return true
    } else {
        return false
    }
}

def call(Map pipelineParams) {
    pipeline {
        agent any

        options {
            disableConcurrentBuilds()
            //skipDefaultCheckout(true)
        }

        stages {

            stage('Is Bump Commit?') {

                when {
                    expression {
                        lastCommitIsBumpCommit()
                    }
                }


                steps {
                    echo "This is a bump commit build - exiting early"
                }
            }

            stage('Checkout') {

                when {
                    expression {
                        env.GIT_BRANCH == 'develop' && !lastCommitIsBumpCommit()
                    }
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

            stage('CI Build') {

                when {
                    expression {
                        env.GIT_BRANCH != 'master' && env.GIT_BRANCH != 'develop' && !lastCommitIsBumpCommit()
                    }
                }

                steps {
                    sh 'mvn -B -DskipTests clean package'
                }
            }

            stage('CI Test') {

                when {
                    expression {
                        env.GIT_BRANCH != 'master' && env.GIT_BRANCH != 'develop' && !lastCommitIsBumpCommit()
                    }
                }

                steps {
                    sh 'mvn -B -DskipTests clean package'
                }
            }

            stage('Maven version update') {

                when {
                    expression {
                        pipelineParams.buildType == 'maven' && env.GIT_BRANCH == 'develop' && !lastCommitIsBumpCommit()
                    }
                }

                steps {

                    dir('project') {
                        sh 'mvn release:update-versions -B'
                        sh 'git add pom.xml'
                        sh 'git commit -m \'[Automated commit: version bump]\''

                        sshagent(credentials: ['ssh']) {
                            sh("git push origin ${env.GIT_BRANCH}")
                        }
                    }
                }
            }

            stage('Compose deployment update') {

                when {
                    expression {
                        env.GIT_BRANCH == 'master' || env.GIT_BRANCH == 'develop' && !lastCommitIsBumpCommit()
                    }
                }

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
                            sh """
                                IMAGE=\$(echo ${pipelineParams.imageName} | sed 's/\\//\\\\\\//g')
                                COMPOSE_FILE=docker-compose.yaml
                                PROJECT_DIR=../project
                                REMOTE_BRANCH=${env.GIT_BRANCH}
                                
                                
                                SNAPSHOT=\$(mvn -f \$PROJECT_DIR/pom.xml -q -Dexec.executable=echo -Dexec.args='\${project.version}' --non-recursive exec:exec)
                                
                                sed -i -E "s/\$IMAGE.+/\$IMAGE\$SNAPSHOT/" \$COMPOSE_FILE
                                
                                if [ \$(git diff | wc -l) -gt 0 ]; then
                                    git add docker-compose.yaml
                                    git commit -m "New release"
                                    git push origin \$REMOTE_BRANCH
                                fi

                            """
                           // sh "./update.sh ${pipelineParams.imageName} docker-compose.yaml ../project ${env.GIT_BRANCH}"
                        }
                    }
                }
            }

            stage('Docker build and tag') {

                when {
                    expression {
                        env.GIT_BRANCH == 'develop' && !lastCommitIsBumpCommit()
                    }
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
                    expression {
                        env.GIT_BRANCH == 'develop' && !lastCommitIsBumpCommit()
                    }
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
                    expression {
                        env.GIT_BRANCH == 'master' && !lastCommitIsBumpCommit()
                    }
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

