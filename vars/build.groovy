class build {

    def pipeline = new customPipeline()

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
                pipeline.step('Maven', pipelineParams.buildType == 'maven', {

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
                pipeline.step('Gulp', pipelineParams.buildType == 'gulp', {

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