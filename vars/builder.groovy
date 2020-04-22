import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call(Map pipelineParams) {

    def String SOURCE_BRANCH
    def String SOURCE_URL

    def Boolean IS_BUMP_COMMIT

    def String DOCKER_TAG_VERSION
    def String PROJECT_VERSION

    def String PROJECT_DIR
    def String DEPLOYMENT_DIR

    def Boolean SHOULD_PUSH_DOCKER

    node {
        properties([
                disableConcurrentBuilds()
        ])

        stage('Clean') {

            cleanWs()
            
            dir('project') {
                deleteDir()
            }

            dir('deployment') {
                deleteDir()
            }

            SOURCE_BRANCH = "${BRANCH_NAME}"
            SOURCE_URL = "${scm.userRemoteConfigs[0].url}"
            IS_BUMP_COMMIT = false
            SHOULD_PUSH_DOCKER = false

            echo "Source branch: ${SOURCE_BRANCH}"
            echo "Source Url: ${SOURCE_URL}"
            echo "Git Branch: ${GIT_BRANCH}"
            echo "Git Branch: ${CHANGE_BRANCH}"
        }

        stage('Pipeline setup') {

            customParallel([
                    step('Checkout Project', {

                        dir('project') {

                            git(
                                    branch: "${SOURCE_BRANCH}",
                                    url: "${SOURCE_URL}",
                                    credentialsId: 'ssh'
                            )

                            script {
                                PROJECT_DIR = pwd()
                                IS_BUMP_COMMIT = lastCommitIsBumpCommit()
                            }
                        }
                    }),
                    step('Create pipeline scripts', {

                        dir('project') {

                            script {
                                createScript('increment_version.sh')
                            }
                        }
                    })
            ])
        }

        stage('Is Bump Commit?', isSpecialBuild() && IS_BUMP_COMMIT, {
            echo "This is a bump commit build - exiting early"
        })

        stage('Integration Test') {

            def String unique_Id = UUID.randomUUID().toString()

            customParallel([
                    step('Maven', pipelineParams.buildType == 'maven', {

                        try {
                            dir("$PROJECT_DIR") {
                                sh "docker build -f ${pipelineParams.test} . -t ${unique_Id}"
                                sh "docker run --name ${unique_Id} ${unique_Id} mvn surefire-report:report"
                                sh "docker cp \$(docker ps -aqf \"name=${unique_Id}\"):/usr/webapp/target/surefire-reports ."
                            }
                        } finally {
                            dir("$PROJECT_DIR") {
                                junit 'surefire-reports/**/*.xml'
                            }

                            sh "docker rm -f ${unique_Id}"
                            sh "docker rmi ${unique_Id}"
                        }
                    }),
                    step('Gulp', pipelineParams.buildType == 'gulp', {

                        try {
                            dir("$PROJECT_DIR") {
                                sh "docker build -f ${pipelineParams.test} . -t ${unique_Id}"
                                sh "docker run --name ${unique_Id} ${unique_Id} ./node_modules/gulp/bin/gulp test"
                                sh "docker cp \$(docker ps -aqf \"name=${unique_Id}\"):/usr/webapp/tests/junit ."
                            }
                        } finally {
                            dir("$PROJECT_DIR") {
                                junit 'junit/**/*.xml'
                            }

                            sh "docker rm -f ${unique_Id}"
                            sh "docker rmi ${unique_Id}"
                        }
                    })
            ])
        }

        stage('Update project version', isReleaseBuild() && !IS_BUMP_COMMIT, {

            customParallel([
                    step('Maven', pipelineParams.buildType == 'maven', {

                        dir('project') {
                            sh 'mvn versions:set -DremoveSnapshot'
                            sh 'git add pom.xml'
                            sh 'git commit -m "[Automated commit: Project released]"'

                            PROJECT_VERSION = sh(
                                script: 'mvn -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
                                returnStdout: true
                            ).trim()
                        }
                    }),
                    step('Gulp', pipelineParams.buildType == 'gulp', {

                        script {

                            dir('project') {

                                UI_VERSION = sh(
                                        script: "sed -n \"s/^.*appVersion.*'\\(.*\\)'.*\$/\\1/ p\" conf/config-release.js | tr -d '\\n'",
                                        returnStdout: true
                                )

                                PROJECT_VERSION = sh(
                                        script: "./increment_version.sh ${UI_VERSION}",
                                        returnStdout: true
                                ).trim()

                                sh("""
                                    #!/bin/bash
                                    sed -i "s/appVersion: '${UI_VERSION}'/appVersion: '${PROJECT_VERSION}'/g" conf/config-release.js
                                """)

                                sh 'git add conf/config-release.js'
                                sh 'git commit -m "[Automated commit: Project released]"'
                            }
                        }
                    })
            ])
        })

        stage('Docker Building & Re-tagging', isReleaseBuild() || isOpsBuild(), {

            step('Get missing tag', isOpsBuild(), {  
                dir('project') {
                    PROJECT_VERSION = sh([
                            script: 'git describe --tags | sed -n -e "s/\\([0-9]\\)-.*/\\1/ p"',
                            returnStdout: true
                    ]).trim()
                }
            })

            DOCKER_TAG_VERSION = getDockerTag(PROJECT_VERSION)

            customParallel([
                step('Build Docker Image', isReleaseBuild() && !IS_BUMP_COMMIT, {
                    dir('project') {
                        sh "docker build . -t ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                        SHOULD_PUSH_DOCKER = true
                    }
                }),
                step('Re-tag Image', isOpsBuild(), {
                    if(!doesDockerImageExist(pipelineParams.imageName + DOCKER_TAG_VERSION)) {
                        referenceTag = getReferenceTag(PROJECT_VERSION)
                        sh "docker pull ${pipelineParams.imageName}${referenceTag}"
                        sh "docker tag ${pipelineParams.imageName}${referenceTag} ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                        SHOULD_PUSH_DOCKER = true
                    }
                })
            ])
        })

        stage('Prepare project for next iteration', isReleaseBuild() && !IS_BUMP_COMMIT, {

            stage('Tag git release', true, {
                dir('project') {
                    sh "git tag -a ${PROJECT_VERSION} -m \"Release ${PROJECT_VERSION}\""
                }
            })

            customParallel([
                    step('Maven', pipelineParams.buildType == 'maven', {

                        dir('project') {
                            sh "mvn versions:set -DnewVersion=${PROJECT_VERSION}-SNAPSHOT"
                            sh 'mvn release:update-versions -B'
                            sh 'git add pom.xml'
                            sh 'git commit -m "[Automated commit: version bump]"'
                        }
                    })
            ])
        })

        stage('Push Project Updates', isReleaseBuild(), {

            customParallel([
                    step('Push docker image', SHOULD_PUSH_DOCKER, {

                        dir('project') {
                            script {
                                withDockerRegistry([credentialsId: "dockerhub", url: ""]) {
                                    sh "docker push ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                                }
                            }
                        }
                    }),
                    step('Push project update', !IS_BUMP_COMMIT, {

                        dir('project') {
                            sshagent(credentials: ['ssh']) {
                                sh "git push origin ${SOURCE_BRANCH}"
                                sh "git push origin ${PROJECT_VERSION}"
                            }
                        }
                    })
            ])
        })
    }
}

def doesTagExist(tag) {
    exists = sh(
        script: """
        if [ \$(git tag -l \"$tag\") ]; then 
            echo \"yes\"
        fi
        """,
        returnStdout: true).trim()
    return exists == 'yes'
}

def doesDockerImageExist(image) {
    exists = sh(
        script: """
        if [ ! -z "\$(docker images -q ${image} 2> /dev/null)" ]; then 
            echo \"yes\"
        fi
        """,
        returnStdout: true).trim()
    return exists == 'yes'
}

def getDockerTag(version) {
    if(isOpsBuild()) {
        return version
    } else if(isRefBuild()) {
        return version + '-SNAPSHOT'
    } else if(isReleaseBuild()) {
        return version + '-release-candidate'
    }
    throw new Exception("Invalid use of this function")
}

def getReferenceTag(version) {
    if(isOpsBuild()) {
        return version + '-release-candidate'
    } else if(isReleaseBuild()) {
        return version + '-SNAPSHOT'
    }
    throw new Exception("Invalid use of this function")
}

def lastCommitIsBumpCommit() {
    lastCommit = sh([script: 'git log -1', returnStdout: true])
    if (lastCommit.contains("[Automated commit: version bump]")) {
        return true
    } else {
        return false
    }
}

def isSpecialBuild() {
    return isRefBuild() || isOpsBuild() || isReleaseBuild()
}

def isRefBuild() {
    return BRANCH_NAME == 'develop'
}

def isOpsBuild() {
    return BRANCH_NAME == 'master'
}

def isReleaseBuild() {
    return BRANCH_NAME.startsWith('release/')
}

def createScript(scriptName) {
    def scriptContent = libraryResource "com/corp/pipeline/scripts/${scriptName}"
    writeFile file: "${scriptName}", text: scriptContent
    sh "chmod +x ${scriptName}"
}

def stage(name, execute, block) {
    return stage(name, execute ? block : {
        echo "skipped stage $name"
        Utils.markStageSkippedForConditional(name)
    })
}

def step(name, block) {
    return step(name, true, block)  
}

def step(name, execute, block) {
    return [
            'name': name,
            'value': execute ? block : {
                echo "skipped stage $name"
                Utils.markStageSkippedForConditional(name)
            }
    ]
}

def customParallel(steps) {
    def map = [:]

    steps.each { step ->
        map.put(step.name, step.value)
    }

    parallel(map)
}

