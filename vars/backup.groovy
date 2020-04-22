


if(isRefBuild()) {
    def Boolean createRelease = false

    // Input Step
    timeout(time: 1, unit: "MINUTES") {
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





        // stage('Get deployment version', (isRefBuild() || isReleaseBuild()) && !IS_BUMP_COMMIT, {

        //     customParallel([

        //             step('Maven', pipelineParams.buildType == 'maven', {

        //                 dir('project') {
        //                     script {
        //                         PROJECT_VERSION = sh(
        //                                 script: 'mvn -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
        //                                 returnStdout: true
        //                         ).trim()
        //                     }
        //                 }
        //             }),
        //             step('Gulp', pipelineParams.buildType == 'gulp', {

        //                 script {

        //                     dir('project') {
        //                         PROJECT_VERSION = sh(
        //                                 script: "sed -n \"s/^.*appVersion.*'\\(.*\\)'.*\$/\\1/ p\" conf/config-release.js | tr -d '\\n'",
        //                                 returnStdout: true
        //                         )
        //                     }
        //                 }
        //             })
        //     ])
        // })

        //                    step('Checkout Deployment', isSpecialBuild(), {
//
//                        dir('deployment') {
//                            git(
//                                    branch: "${SOURCE_BRANCH}",
//                                    url: "${pipelineParams.deploymentRepo}",
//                                    credentialsId: 'ssh'
//                            )
//
//                            script {
//                                DEPLOYMENT_DIR = pwd()
//                            }
//                        }
//                    }),