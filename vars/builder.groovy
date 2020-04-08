def call(Map pipelineParams) {
    pipeline {
        agent any

        stages {

            stage('Build') { // for display purposes
                steps {
            //        sh 'mkdir -p project'

                    dir('project') {

                        sh 'pwd; ls'

                        // Get some code from a GitHub repository
                        git(
                                branch: "${env.GIT_BRANCH}",
                                url: "${env.GIT_URL}",
                                credentialsId: 'ssh-github'
                        )

                        sh 'pwd; ls'
                    }
                }

            }

            stage('Maven Build') {
                when {
                    expression {
                        pipelineParams.buildType == 'maven'
                    }
                }

                steps {
                    sh 'pwd; ls'

                    sh 'mvn release:update-versions -B'
                    sh 'git add pom.xml'
                    sh 'git commit -m \'Automated commit: release project\''

                    sshagent (credentials: ['ssh-github']) {
                        sh('git push origin master')
                    }

                    sh 'pwd; ls'
                    //sh 'mvn clean build -Ddocker'
                }
            }

            stage('Update deployment file') {
                steps {

                    sh 'MVN_VERSION=$(mvn -q \\\n' +
                            '    -Dexec.executable=echo \\\n' +
                            '    -Dexec.args=\'${project.version}\' \\\n' +
                            '    --non-recursive \\\n' +
                            '    exec:exec)'

                    sh 'echo $MVN_VERSION'

                    sh 'pwd; ls'


                    git(
                        branch: "${pipelineParams.deploymentBranch}",
                        url: "${pipelineParams.deploymentRepo}",
                        credentialsId: 'ssh-github'
                    )

                    sh 'pwd; ls'

                    sh 'sed -i -E "s/${pipelineParams.imageName}.+/${pipelineParams.imageName}$MVN_VERSION/" docker-compose.yaml'
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

