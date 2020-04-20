import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call(Map pipelineParams) {

    def String SOURCE_COMMIT = ''
    def String SOURCE_BRANCH = "${BRANCH_NAME}"
    def String SOURCE_URL = "${scm.userRemoteConfigs[0].url}"
    def String IS_BUMP_COMMIT = false
    def String DOCKER_TAG_VERSION = 'EXAMPLE'
    def String PROJECT_DIR = ''
    def String DEPLOYMENT_DIR = ''

    node {
        properties([
                disableConcurrentBuilds()
        ])

        stage('Pipeline setup') {

            customParallel([
                    step('Checkout Project', true, {

                        dir('project') {

                            git(
                                    branch: "${SOURCE_BRANCH}",
                                    url: "${SOURCE_URL}",
                                    credentialsId: 'ssh'
                            )

                            script {
                                SOURCE_COMMIT = sh(returnStdout: true, script: 'git rev-parse HEAD')
                                PROJECT_DIR = pwd()
                                IS_BUMP_COMMIT = lastCommitIsBumpCommit()
                            }
                        }
                    }),
                    step('Checkout Deployment', isOpsBuild() || isRefBuild(), {

                        dir('deployment') {
                            git(
                                    branch: "${SOURCE_BRANCH}",
                                    url: "${pipelineParams.deploymentRepo}",
                                    credentialsId: 'ssh'
                            )

                            script {
                                DEPLOYMENT_DIR = pwd()
                            }
                        }
                    }),
                    step('Create pipeline scripts', true, {

                        dir('project') {

                            script {
                                createScript('increment_version.sh')
                            }
                        }
                    })
            ])
        }

        stage('Is Bump Commit?', isOpsBuild() && isRefBuild(), {

            echo "This is a bump commit build - exiting early"

            script {
                currentBuild.result = currentBuild.getPreviousBuild().result
            }
        })

        stage('CI Build & Test', !isOpsBuild() && !isRefBuild(), {

            def String unique_Id = UUID.randomUUID().toString()

            customParallel([
                    stage('Maven', pipelineParams.buildType == 'maven', {

                        dir("$PROJECT_DIR") {
                            sh "docker build -f ${pipelineParams.test} . -t ${unique_Id}"
                            sh "docker run --name ${unique_Id} ${unique_Id} mvn surefire-report:report"
                            sh "docker cp \$(docker ps -aqf \"name=${unique_Id}\"):/usr/webapp/target/surefire-reports ."
                        }

                        post {
                            always {
                                dir("$PROJECT_DIR") {
                                    junit 'surefire-reports/**/*.xml'
                                }

                                sh "docker rm -f ${unique_Id}"
                                sh "docker rmi ${unique_Id}"
                            }
                        }
                    }),
                    stage('Gulp', pipelineParams.buildType == 'gulp', {

                        dir("$PROJECT_DIR") {
                            sh "docker build -f ${pipelineParams.test} . -t ${unique_Id}"
                            sh "docker run --name ${unique_Id} ${unique_Id} ./node_modules/gulp/bin/gulp test"
                            sh "docker cp \$(docker ps -aqf \"name=${unique_Id}\"):/usr/webapp/tests/junit ."
                        }

                        post {
                            always {
                                dir("$PROJECT_DIR") {
                                    junit 'junit//**/*.xml'
                                }

                                sh "docker rm -f ${unique_Id}"
                                sh "docker rmi ${unique_Id}"
                            }
                        }
                    })
            ])
        })

        stage('Update project version', isRefBuild() && !IS_BUMP_COMMIT, {

            customParallel([

                    stage('Maven', pipelineParams.buildType == 'maven', {

                        dir('project') {
                            sh 'mvn release:update-versions -B'
                            sh 'git add pom.xml'
                            sh 'git commit -m "[Automated commit: version bump]"'
                        }
                    }),
                    stage('Gulp', pipelineParams.buildType == 'gulp', {

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
                    })
            ])
        })

        stage('Get deployment version', isOpsBuild() || isRefBuild(), {

            customParallel([

                    step('Maven', pipelineParams.buildType == 'maven', {

                        dir('project') {
                            script {
                                DOCKER_TAG_VERSION = sh(
                                        script: 'mvn -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
                                        returnStdout: true
                                ).trim()
                            }
                        }
                    }),
                    step('Gulp', pipelineParams.buildType == 'gulp', {

                        script {

                            dir('project') {
                                DOCKER_TAG_VERSION = sh(
                                        script: "sed -n \"s/^.*appVersion.*'\\(.*\\)'.*\$/\\1/ p\" conf/config-release.js | tr -d '\\n'",
                                        returnStdout: true
                                )
                            }
                        }
                    })
            ])
        })

        stage('Docker build and tag', isRefBuild() && !IS_BUMP_COMMIT, {

            dir('project') {

                script {
                    sh "docker build . -t ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                }
            }
        })

        stage('Push Project Updates', isRefBuild() && !IS_BUMP_COMMIT, {

            customParallel([
                    step('Push docker image') {

                        dir('project') {
                            script {
                                withDockerRegistry([credentialsId: "dockerhub", url: ""]) {
                                    sh "docker push ${pipelineParams.imageName}${DOCKER_TAG_VERSION}"
                                }
                            }
                        }
                    },
                    step('Push project update') {

                        dir('project') {
                            sshagent(credentials: ['ssh']) {
                                sh "git push origin ${SOURCE_BRANCH}"
                            }
                        }
                    }
            ])
        })

        stage('Update compose version', isOpsBuild() || isRefBuild(), {

            stage('Update file') {

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

            stage('Push File') {
                dir('deployment') {
                    sshagent(credentials: ['ssh']) {
                        sh "git push origin ${SOURCE_BRANCH}"
                    }
                }
            }
        })
    }


    if(isRefBuild()) {
        def Boolean createRelease = false

        // Input Step
        timeout(time: 15, unit: "MINUTES") {
            createRelease = input(
                    message: "Do you want to create a release from this branch",
                    ok: 'Submit',
                    parameters: [
                            booleanParam(
                                    defaultValue: true,
                                    description: 'This will create a release candidate image',
                                    name: 'Create release branch'
                            )
                    ],
                    submitter: 'john'
            )
            echo("Input : ${createRelease}")
        }

        if (createRelease) {

            node {
                properties([
                        disableConcurrentBuilds()
                ])

                stage('Create Release') {

                    dir('project') {
                        script {
                            echo "Commit: ${SOURCE_BRANCH}"
                            echo "Docker: ${DOCKER_TAG_VERSION}"
                        }

                        git(
                                branch: "${SOURCE_BRANCH}",
                                url: "${SOURCE_URL}",
                                credentialsId: 'ssh'
                        )

                        sh "git checkout ${SOURCE_COMMIT}"

                        sh "git checkout -b release/release-${DOCKER_TAG_VERSION}"
                        sh "git push origin release/release-${DOCKER_TAG_VERSION}"
                    }
                }
            }
        }
    }

    def deploy = input(
            message: "Do you want to deploy ${DOCKER_TAG_VERSION}",
            ok: 'Submit',
            parameters: [
                    booleanParam(
                            defaultValue: false,
                            description: 'This will update the deployment file and deploy the image',
                            name: 'Deploy image'
                    )
            ],
            submitter: 'john'
    )
    echo("Input : ${deploy}")

    if (deploy) {

        node {
            properties([
                    disableConcurrentBuilds()
            ])

            stage('Deploy') {

                customParallel([
                        step('Deploy to Ref', isRefBuild(), {

                            dir('deployment') {

                                build job: 'Deploy', parameters: [
                                        [$class: 'StringParameterValue', name: 'environment', value: "ref"],
                                        [$class: 'StringParameterValue', name: 'repo', value: "${pipelineParams.deploymentRepo}"],
                                        [$class: 'StringParameterValue', name: 'branch', value: "${SOURCE_BRANCH}"]
                                ]
                            }
                        }),
                        step('Deploy to Ops', isOpsBuild(), {

                            dir('deployment') {

                                build job: 'Deploy', parameters: [
                                        [$class: 'StringParameterValue', name: 'environment', value: "ops"],
                                        [$class: 'StringParameterValue', name: 'repo', value: "${pipelineParams.deploymentRepo}"],
                                        [$class: 'StringParameterValue', name: 'branch', value: "${SOURCE_BRANCH}"]
                                ]
                            }
                        })
                ])
            }
        }
    }
}

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

