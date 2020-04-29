class build {
    def pipeline

    build() {
        pipeline = new customPipeline()
    }

    def clean(source_branch) {
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

    def integration(project_dir, is_pull_request, source_branch, pipeline_params) {
        if(is_pull_request) {
            dir("${project_dir}") {
                sh """
                    git checkout develop
                    git pull origin develop
                    git checkout ${source_branch}
                    git merge develop
                """
            }
        }

        def String unique_id = UUID.randomUUID().toString()

        pipeline.customParallel([
                pipeline.step('Maven', pipeline_params.buildType == 'maven', {

                    try {
                        dir("$project_dir") {
                            sh "docker build -f ${pipeline_params.test} . -t ${unique_id}"
                            sh "docker run --name ${unique_id} ${unique_id} mvn surefire-report:report"
                            sh "docker cp \$(docker ps -aqf \"name=${unique_id}\"):/usr/webapp/target/surefire-reports ."
                        }
                    } finally {
                        dir("$project_dir") {
                            junit 'surefire-reports/**/*.xml'
                        }

                        sh "docker rm -f ${unique_id}"
                        sh "docker rmi ${unique_id}"
                    }
                }),
                pipeline.step('Gulp', pipeline_params.buildType == 'gulp', {

                    try {
                        dir("$project_dir") {
                            sh "docker build -f ${pipeline_params.test} . -t ${unique_id}"
                            sh "docker run --name ${unique_id} ${unique_id} ./node_modules/gulp/bin/gulp test"
                            sh "docker cp \$(docker ps -aqf \"name=${unique_id}\"):/usr/webapp/tests/junit ."
                        }
                    } finally {
                        dir("$project_dir") {
                            junit 'junit/**/*.xml'
                        }

                        sh "docker rm -f ${unique_id}"
                        sh "docker rmi ${unique_id}"
                    }
                })
        ])
    }
}