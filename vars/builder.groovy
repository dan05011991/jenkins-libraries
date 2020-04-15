
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

def createScript(scriptName) {
    def scriptContent = libraryResource "com/corp/pipeline/scripts/${scriptName}"
    writeFile file: "${scriptName}", text: scriptContent
    sh "chmod +x ${scriptName}"
}

def call(Map pipelineParams) {
    pipeline {
        agent any

        environment {
            SOURCE_BRANCH = "${BRANCH_NAME}"
            SOURCE_URL = "${scm.userRemoteConfigs[0].url}"
            IS_BUMP_COMMIT = true
            DOCKER_TAG_VERSION = ''
            MAVEN_IMAGE = 'maven:3.6.3-jdk-8'
            NODE_IMAGE = 'node:alpine3.11'
        }

        options {
            disableConcurrentBuilds()
            skipDefaultCheckout()
        }

        stages {

            stage('Pipeline setup') {

                parallel {

                    stage('Checkout Project') {
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
                        }
                    }

                    stage('Checkout Deployment') {

                        when {
                            expression {
                                isOpsBuild() || isRefBuild()
                            }
                        }

                        steps {

                            dir('deployment') {
                                git(
                                        branch: "${env.SOURCE_BRANCH}",
                                        url: "${pipelineParams.deploymentRepo}",
                                        credentialsId: 'ssh'
                                )
                            }
                        }
                    }

                    stage('Create pipeline scripts') {

                        steps {

                            dir('project') {

                                script {
                                    createScript('increment_version.sh')
                                }
                            }
                        }
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

                environment {
                    unique_Id = UUID.randomUUID().toString()
                }

                parallel {

                    stage('Maven') {

                        agent {
                            docker {
                                image MAVEN_IMAGE
                                args '-v ${PWD}:/usr/src/app -w /usr/src/app'
                            }
                        }

                        when {
                            expression {
                                pipelineParams.buildType == 'maven'
                            }
                        }

                        steps {
                            dir('project') {
                                sh "mvn surefire-report:report"
                            }
                        }

                        post {
                            always {
                                dir('project') {
                                    junit 'target/surefire-reports/**/*.xml'
                                }
                            }
                        }
                    }

                    stage('Gulp') {

                        when {
                            expression {
                                pipelineParams.buildType == 'gulp'
                            }
                        }

                        steps {
                            dir('project') {
                                sh "docker build -f test.dockerfile . -t ${unique_Id}"
                                sh "docker run ${unique_Id}"
                            }
                        }

                        post {
                            always {
                                dir('project') {
                                    junit 'target/surefire-reports/**/*.xml'
                                }
                            }
                        }
                    }
                }
            }

            stage('Update project version') {

                when {
                    expression {
                        isRefBuild() && !IS_BUMP_COMMIT
                    }
                }

                parallel {

                    stage('Maven') {

                        when {
                            expression {
                                pipelineParams.buildType == 'maven'
                            }
                        }

                        steps {

                            dir('project') {
                                sh 'mvn release:update-versions -B'
                                sh 'git add pom.xml'
                                sh 'git commit -m "[Automated commit: version bump]"'
                            }

                        }
                    }

                    stage('Gulp') {

                        when {
                            expression {
                                pipelineParams.buildType == 'gulp'
                            }
                        }

                        steps {

                            script {

                                dir('project') {



                                    UI_VERSION = sh(
                                            script: "sed -n \"s/^.*appVersion.*'\\(.*\\)'.*\$/\\1/ p\" conf/config-release.js | tr -d '\\n'",
                                            returnStdout: true
                                    )

                                    DOCKER_TAG_VERSION = sh(
                                            script: "./increment_version.sh ${UI_VERSION}",
                                            returnStdout: true
                                    ).trim()

                                    sh("""
                                        #!/bin/bash
                                        sed -i "s/appVersion: '${UI_VERSION}'/appVersion: '${DOCKER_TAG_VERSION}'/g" conf/config-release.js
                                    """)

                                    sh 'git add conf/config-release.js'
                                    sh 'git commit -m "[Automated commit: version bump]"'
                                }
                            }
                        }

                    }
                }
            }

            stage('Get deployment version') {

                when {
                    expression {
                        isOpsBuild() || isRefBuild()
                    }
                }

                parallel {

                    stage('Maven') {

                        when {
                            expression {
                                pipelineParams.buildType == 'maven'
                            }
                        }

                        steps {

                            dir('project') {
                                script {
                                    DOCKER_TAG_VERSION = sh(
                                            script: 'mvn -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
                                            returnStdout: true
                                    ).trim()
                                }
                            }
                        }
                    }

                    stage('Gulp') {

                        when {
                            expression {
                                pipelineParams.buildType == 'gulp'
                            }
                        }

                        steps {

                            script {

                                dir('project') {
                                    DOCKER_TAG_VERSION = sh(
                                            script: "sed -n \"s/^.*appVersion.*'\\(.*\\)'.*\$/\\1/ p\" conf/config-release.js | tr -d '\\n'",
                                            returnStdout: true
                                    )
                                }
                            }
                        }
                    }
                }
            }

            stage('Update compose version') {

                when {
                    expression {
                        isOpsBuild() || isRefBuild()
                    }
                }

                steps {

                    dir('deployment') {

                        sshagent(credentials: ['ssh']) {
                            sh """
                        IMAGE=\$(echo ${pipelineParams.imageName} | sed 's/\\//\\\\\\//g')
                        COMPOSE_FILE=docker-compose.yaml
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

            stage('Push Project Updates') {

                when {
                    expression {
                        isRefBuild() && !IS_BUMP_COMMIT
                    }
                }

                parallel {
                    stage('Push docker image') {

                        steps {
                            dir('project') {
                                script {
                                    withDockerRegistry([credentialsId: "dockerhub", url: ""]) {
                                        sh "docker push ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                                    }
                                }
                            }
                        }
                    }

                    stage('Push project update') {

                        steps {
                            dir('project') {
                                sshagent(credentials: ['ssh']) {
                                    sh "git push origin ${SOURCE_BRANCH}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Push compose update') {

                when {
                    expression {
                        (isOpsBuild() || isRefBuild()) && !IS_BUMP_COMMIT
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

            stage('Deploy') {
                parallel {
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
    }
}

