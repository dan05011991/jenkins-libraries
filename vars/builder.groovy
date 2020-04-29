import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import objects.customPipeline
import objects.build

def call(Map pipelineParams) {

    def build build = new build()

    def String SOURCE_BRANCH
    def String SOURCE_URL
    def Boolean IS_PR

    def Boolean IS_BUMP_COMMIT

    def String DOCKER_TAG_VERSION
    def String PROJECT_VERSION

    def String PROJECT_DIR
    def String DEPLOYMENT_DIR

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

            if (env.BRANCH_NAME.startsWith('PR-')) {
                SOURCE_BRANCH = CHANGE_BRANCH
                IS_PR = true
            } else {
                SOURCE_BRANCH = BRANCH_NAME
                IS_PR = false
            }

            echo "Source branch: ${SOURCE_BRANCH}"
            echo "Source Url: ${SOURCE_URL}"
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
            build.integration()
        }

        stage('Update project version', isReleaseBuild() && !IS_BUMP_COMMIT, {

            dir('project') {
                gitTag = sh([
                        script: 'git describe --tags | sed -n -e "s/\\([0-9]\\)-.*/\\1/ p"',
                        returnStdout: true
                ]).trim()
            }

            PROJECT_VERSION = getNewReleaseVersion(pipelineParams.projectKey, gitTag)

            customParallel([
                    step('Maven', pipelineParams.buildType == 'maven', {

                        dir('project') {
                            sh "mvn versions:set -DnewVersion=${PROJECT_VERSION}"
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

            stage('Get missing tag', isOpsBuild(), {  
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
                    }
                }),
                step('Re-tag Image', isOpsBuild(), {
                    if(!doesDockerImageExist(pipelineParams.imageName + DOCKER_TAG_VERSION)) {
                        referenceTag = getReferenceTag(PROJECT_VERSION)
                        sh "docker pull ${pipelineParams.imageName}${referenceTag}"
                        sh "docker tag ${pipelineParams.imageName}${referenceTag} ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
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

        stage('Push Docker Updates', (isReleaseBuild() && !IS_BUMP_COMMIT) || isOpsBuild(), {
            dir('project') {
                script {
                    withDockerRegistry([credentialsId: "dockerhub", url: ""]) {
                        sh "docker push ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                    }
                }
            }
        })

        stage('Push Project Updates', (isReleaseBuild() || isOpsBuild()) && !IS_BUMP_COMMIT, {
            dir('project') {
                sshagent(credentials: ['ssh']) {
                    sh "git push origin ${SOURCE_BRANCH}"
                    sh "git push origin ${PROJECT_VERSION}"
                }
            }
        })
    }
}

def getNewReleaseVersion(key, tag) {
    type = getIncrementType()
    def job = build job: 'SemVer', parameters: [
            string(name: 'PROJECT_KEY', value: "${key}"),
            string(name: 'RELEASE_TYPE', value: "${type}"),
            string(name: 'GIT_TAG', value: "${tag}")
        ], 
        propagate: true, 
        wait: true
        
    def jobResult = job.getResult()
     
    copyArtifacts(
        fingerprintArtifacts: true, 
        projectName: 'SemVer', 
        selector: specific("${job.number}")
    )
    
    version = sh(
        script: 'cat version',  
        returnStdout: true
        ).trim()
    
    return version
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
    return BRANCH_NAME.startsWith('release/') || BRANCH_NAME.startsWith('hotfix/')
}

def getIncrementType() {
    if(BRANCH_NAME.startsWith('release/')) {
        return 'm';
    } else if(BRANCH_NAME.startsWith('hotfix/')) {
        return 'p'
    }
    throw new Exception('Incorrect use of this function');
}

def createScript(scriptName) {
    def scriptContent = libraryResource "com/corp/pipeline/scripts/${scriptName}"
    writeFile file: "${scriptName}", text: scriptContent
    sh "chmod +x ${scriptName}"
}

