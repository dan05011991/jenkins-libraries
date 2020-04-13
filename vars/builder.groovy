
def lastCommitIsBumpCommit() {
    lastCommit = sh([script: 'git log -1', returnStdout: true])
    if (lastCommit.contains("[Automated commit: version bump]")) {
        return true
    } else {
        return false
    }
}

def call(Map pipelineParams) {
    pipeline {
        agent any

        options {
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'docker_tag_version', defaultValue: '')
        }

        stages {

            stage('Is Bump Commit?') {

                when {
                    expression {
                        lastCommitIsBumpCommit()
                    }
                }


                steps {
                    echo "This is a bump commit build - exiting early"

                    script {
                        currentBuild.result = currentBuild.getPreviousBuild().result
                    }
                }
            }

            stage('Checkout') {

                steps {
                    dir('project') {

                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${env.GIT_URL}",
                            credentialsId: 'ssh'
                        )
                    }

                    dir('deployment') {
                        git(
                            branch: "${env.GIT_BRANCH}",
                            url: "${pipelineParams.deploymentRepo}",
                            credentialsId: 'ssh'
                        )
                    }
                }
            }

            stage('CI Build & Test') {

                when {
                    expression {
                        env.GIT_BRANCH != 'master' && env.GIT_BRANCH != 'develop' && !lastCommitIsBumpCommit()
                    }
                }

                steps {
                    sh 'mvn -B -DskipTests clean package'
                }
            }

            stage('Maven version update') {

                when {
                    expression {
                        pipelineParams.buildType == 'maven' && env.GIT_BRANCH == 'develop' && !lastCommitIsBumpCommit()
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
                        env.GIT_BRANCH == 'master' || env.GIT_BRANCH == 'develop'
                    }
                }

                steps {


                    dir('deployment') {

                        script {
                            env.docker_tag_version = sh(
                                    script: 'mvn -f ../project/pom.xml -q -Dexec.executable=echo -Dexec.args=\'${project.version}\' --non-recursive exec:exec',
                                    returnStdout: true
                            ).trim()
                        }

                        sshagent(credentials: ['ssh']) {
                            sh """
                                IMAGE=\$(echo ${pipelineParams.imageName} | sed 's/\\//\\\\\\//g')
                                COMPOSE_FILE=docker-compose.yaml
                                PROJECT_DIR=../project
                        
                                sed -i -E "s/\$IMAGE.+/\$IMAGE${env.docker_tag_version}/" \$COMPOSE_FILE
                                
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
                        env.GIT_BRANCH == 'develop' && !lastCommitIsBumpCommit()
                    }
                }

                steps {

                    dir('project') {

                        script {
                            sh "docker build . -t ${pipelineParams.imageName}${env.docker_tag_version}"

                            withDockerRegistry([ credentialsId: "dockerhub", url: "" ]) {
                                sh "docker push ${pipelineParams.imageName}${env.docker_tag_version}"
                            }
                        }
                    }
                }
            }

            stage('Commit changes') {
                steps {
                    dir('project') {
                        sshagent(credentials: ['ssh']) {
                            sh "git push origin ${env.GIT_BRANCH}"
                        }
                    }

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
                        env.GIT_BRANCH == 'develop' && !lastCommitIsBumpCommit()
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
                        env.GIT_BRANCH == 'master' && !lastCommitIsBumpCommit()
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

