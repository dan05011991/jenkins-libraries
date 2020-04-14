
def lastCommitIsBumpCommit() {
    lastCommit = sh([script: 'git log -1', returnStdout: true])
    if (lastCommit.contains("[Automated commit: version bump]")) {
        return true
    } else {
        return false
    }
}

def isRefBuild() {
    return BRANCH_NAME == 'develop'
}

def isOpsBuild() {
    return BRANCH_NAME == 'master'
}

def call(Map pipelineParams) {
    pipeline {
        agent any

        environment {
            SOURCE_BRANCH = "${BRANCH_NAME}"
            SOURCE_URL = "${scm.userRemoteConfigs[0].url}"
            IS_BUMP_COMMIT = true
            DOCKER_TAG_VERSION = ''
        }

        options {
            disableConcurrentBuilds()
            skipDefaultCheckout()
        }

        stages {

            stage('Checkout') {

                steps {

                    dir('project') {

                        git(
                            branch: "${env.SOURCE_BRANCH}",
                            url: "${env.SOURCE_URL}",
                            credentialsId: 'ssh'
                        )

                        script {
                            IS_BUMP_COMMIT = lastCommitIsBumpCommit()
                        }
                    }

                    dir('deployment') {
                        git(
                            branch: "${env.SOURCE_BRANCH}",
                            url: "${pipelineParams.deploymentRepo}",
                            credentialsId:'ssh'
                        )
                    }

                    script {
                        echo "Variables:"
                        echo "SOURCE_BRANCH: ${SOURCE_BRANCH}"
                        echo "SOURCE_URL: ${SOURCE_URL}"
                        echo "IS_BUMP_COMMIT: ${IS_BUMP_COMMIT}"
                    }
                }
            }

            stage('Is Bump Commit?') {

                when {
                    expression {
                        IS_BUMP_COMMIT
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
                        !isOpsBuild() && !isRefBuild()
                    }
                }

                steps {
                    sh 'mvn -B -DskipTests clean package'
                }
            }

            stage('Maven version update') {

                when {
                    expression {
                        pipelineParams.buildType == 'maven' && isRefBuild() && !IS_BUMP_COMMIT
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
                        isOpsBuild() || isRefBuild() && !IS_BUMP_COMMIT
                    }
                }

                steps {

                    dir('deployment') {

                        script {
                            DOCKER_TAG_VERSION = sh(
                                script: 'mvn -f ../project/pom.xml -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
                                returnStdout: true
                            ).trim()
                        }

                        sshagent(credentials: ['ssh']) {
                            sh """
                                IMAGE=\$(echo ${pipelineParams.imageName} | sed 's/\\//\\\\\\//g')
                                COMPOSE_FILE=docker-compose.yaml
                                PROJECT_DIR=../project
                                SNAPSHOT=${DOCKER_TAG_VERSION}
                        
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
                        isRefBuild() && !IS_BUMP_COMMIT
                    }
                }

                steps {

                    dir('project') {

                        script {
                            sh "docker build . -t ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                        }
                    }
                }
            }

            stage('Push docker image') {

                when {
                    expression {
                        isRefBuild() && !IS_BUMP_COMMIT
                    }
                }

                steps {
                    dir('project') {
                        script {
                            withDockerRegistry([ credentialsId: "dockerhub", url: "" ]) {
                                sh "docker push ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                            }
                        }
                    }
                }
            }

            stage('Push pom update') {

                when {
                    expression {
                        isRefBuild() && !IS_BUMP_COMMIT
                    }
                }

                steps {
                    dir('project') {
                        sshagent(credentials: ['ssh']) {
                            sh "git push origin ${SOURCE_BRANCH}"
                        }
                    }
                }
            }

            stage('Push compose update') {

                when {
                    expression {
                        !IS_BUMP_COMMIT
                    }
                }

                steps {
                    dir('deployment') {
                        sshagent(credentials: ['ssh']) {
                            sh "git push origin ${SOURCE_BRANCH}"
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

                        git(
                                branch: "${SOURCE_BRANCH}",
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

