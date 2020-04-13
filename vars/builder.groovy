
def lastCommitIsBumpCommit() {
    lastCommit = sh([script: 'git log -1', returnStdout: true])
    if (lastCommit.contains("[Automated commit: version bump]")) {
        return true
    } else {
        return false
    }
}

def SOURCE = scm.userRemoteConfigs[0].url

//def isRefBuild(branch) {
//
//}
//
//def isOpsBuild(branch) {
//
//}

def String docker_tag_version = ''
def Boolean isBumpCommit = false


def call(Map pipelineParams) {
    pipeline {
        agent any

        options {
            disableConcurrentBuilds()
            skipDefaultCheckout()
        }

        stages {

            stage('Checkout') {

                steps {
                    dir('project') {
                        script {
                            println ${SOURCE}
                        }
                        
                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${env.GIT_URL}",
                            credentialsId: 'ssh'
                        )

                        script {
                            isBumpCommit = lastCommitIsBumpCommit()
                        }
                    }

                    dir('deployment') {
                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${pipelineParams.deploymentRepo}",
                            credentialsId: 'ssh'
                        )
                    }
                }
            }

            stage('Is Bump Commit?') {

                when {
                    expression {
                        isBumpCommit
                    }
                }


                steps {
                    echo "This is a bump commit build - exiting early"

                    script {
                        currentBuild.result = currentBuild.getPreviousBuild().result
                    }
                }
            }

            stage('CI Build & Test') {

                when {
                    expression {
                        env.GIT_BRANCH != 'master' && env.GIT_BRANCH != 'develop' && !isBumpCommit
                    }
                }

                steps {
                    sh 'mvn -B -DskipTests clean package'
                }
            }

            stage('Maven version update') {

                when {
                    expression {
                        pipelineParams.buildType == 'maven' && env.GIT_BRANCH == 'develop' && !isBumpCommit
                    }
                }

                steps {

                    dir('project') {
                        sh 'mvn release:update-versions -B'
                        sh 'git add pom.xml'
                        sh 'git commit -m \'[Automated commit: version bump]\''
                    }
                }
            }

            stage('Deployment file update') {

                when {
                    expression {
                        env.GIT_BRANCH == 'master' || env.GIT_BRANCH == 'develop'
                    }
                }

                steps {

                    dir('deployment') {

                        script {
                            docker_tag_version = sh(
                                    script: 'mvn -f ../project/pom.xml -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
                                    returnStdout: true
                            ).trim()
                        }

                        sshagent(credentials: ['ssh']) {
                            sh """
                                IMAGE=\$(echo ${pipelineParams.imageName} | sed 's/\\//\\\\\\//g')
                                COMPOSE_FILE=docker-compose.yaml
                                PROJECT_DIR=../project
                                SNAPSHOT=${docker_tag_version}
                        
                                sed -i -E "s/\$IMAGE.+/\$IMAGE\$SNAPSHOT/" \$COMPOSE_FILE
                                
                                if [ \$(git diff | wc -l) -gt 0 ]; then
                                    git add docker-compose.yaml
                                    git commit -m "[Automated commit: version bump]"
                                fi

                            """
                        }
                    }
                }
            }

            stage('Docker build and tag') {

                when {
                    expression {
                        env.GIT_BRANCH == 'develop' && !isBumpCommit
                    }
                }

                steps {

                    dir('project') {

                        script {
                            sh "docker build . -t ${pipelineParams.imageName}${docker_tag_version}"
                        }
                    }
                }
            }

            stage('Push docker update') {

                when {
                    expression {
                        env.GIT_BRANCH == 'develop' && !isBumpCommit
                    }
                }

                steps {
                    dir('project') {
                        script {
                            withDockerRegistry([ credentialsId: "dockerhub", url: "" ]) {
                                sh "docker push ${pipelineParams.imageName}${docker_tag_version}"
                            }
                        }
                    }
                }
            }

            stage('Push project update') {

                when {
                    expression {
                        !isBumpCommit
                    }
                }

                steps {
                    dir('project') {
                        sshagent(credentials: ['ssh']) {
                            sh "git push origin ${env.GIT_BRANCH}"
                        }
                    }
                }
            }

            stage('Push deployment update') {

                steps {
                    dir('deployment') {
                        sshagent(credentials: ['ssh']) {
                            sh "git push origin ${env.GIT_BRANCH}"
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
                        env.GIT_BRANCH == 'develop'
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
                        env.GIT_BRANCH == 'master'
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

