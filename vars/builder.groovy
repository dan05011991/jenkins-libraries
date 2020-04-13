
def lastCommitIsBumpCommit() {
    lastCommit = sh([script: 'git log -1', returnStdout: true])
    if (lastCommit.contains("[Automated commit: version bump]")) {
        return true
    } else {
        return false
    }
}

def isRefBuild() {
    return sourceBranch == 'develop'
}

def isOpsBuild() {
    return sourceBranch == 'master'
}


def call(Map pipelineParams) {
    def String isBumpCommit = lastCommitIsBumpCommit()
    def String sourceUrl = scm.userRemoteConfigs[0].url
    def String sourceBranch = BRANCH_NAME
    def String cloneType = 'ssh'
    def String docker_tag_version = ''

    
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

                        git(
                            branch: sourceBranch,
                            url: sourceUrl,
                            credentialsId: cloneType
                        )
                    }

                    dir('deployment') {
                        git(
                            branch: sourceBranch,
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
                        !isOpsBuild() && !isRefBuild() && !isBumpCommit
                    }
                }

                steps {
                    sh 'mvn -B -DskipTests clean package'
                }
            }

            stage('Maven version update') {

                when {
                    expression {
                        pipelineParams.buildType == 'maven' && isRefBuild() && !isBumpCommit
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
                        isOpsBuild() || isRefBuild()
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
                        isRefBuild() && !isBumpCommit
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
                        isRefBuild() && !isBumpCommit
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
                        isRefBuild() && !isBumpCommit
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

                when {
                    expression {
                        !isBumpCommit
                    }
                }

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
                        isRefBuild()
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
                        isOpsBuild()
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

