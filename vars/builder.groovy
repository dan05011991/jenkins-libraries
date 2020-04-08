def call(Map pipelineParams) {
    pipeline {
        agent any

        stages {

            stage('Build') { // for display purposes
                steps {
                    // Get some code from a GitHub repository
                    git(
                        branch: "${env.GIT_BRANCH}",
                        url: "${env.GIT_URL}",
                        credentialsId: '2f7c1cda-f99d-415d-9cf7-e79b414112fc'
                    )
                }

            }

            stage('Maven Build') {
                when {
                    expression {
                        pipelineParams.buildType == 'maven'
                    }
                }


                steps {
                    sh 'ls'
                    sh 'mvn release:update-versions -B'
                    sh 'git add pom.xml'
                    sh 'git commit -m \'Automated commit: release project\''

                    withCredentials([usernamePassword(credentialsId: '2f7c1cda-f99d-415d-9cf7-e79b414112fc', passwordVariable: 'Abc19733791***', usernameVariable: 'dan05011991')]) {
                        sh('git push origin master')
                    }

                }
            }


            stage('Deploy to Ref') {
                agent {
                    label 'ref'
                }

                when {
                    branch 'develop'
                }

                steps {
                    sh 'docker-compose -f docker/docker-compose.yaml up -d'
                }
            }


            stage('Deploy to Ops') {
                agent {
                    label 'ops'
                }

                when {
                    branch 'master'
                }

                steps {
                    sh 'docker-compose -f docker/docker-compose.yaml up -d'
                }
            }
        }
    }
}

