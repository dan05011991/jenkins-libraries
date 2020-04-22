def String SOURCE_BRANCH = 'master'
def String REMOTE = 'git@github.com:dan05011991/demo-application-docker.git'

pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
    }

    parameters {
        string(
            name: 'Image', 
            defaultValue: 'DEFAULT', 
            description: 'This is the image you want to deploy'
        )
        string(
            name: 'Tag', 
            defaultValue: '0.0.0', 
            description: 'This is the tag which you wish to deploy'
        )
        booleanParam(
             name: 'Dev', 
             defaultValue: false, 
             description: 'Deploy to developer environment'
        )
        booleanParam(
             name: 'Int', 
             defaultValue: false, 
             description: 'Deploy to integration environment'
        )
        booleanParam(
             name: 'Ref', 
             defaultValue: false, 
             description: 'Deploy to reference environment'
        )
        booleanParam(
             name: 'Ops', 
             defaultValue: false, 
             description: 'Deploy to operational environment'
        )
    }
    
    stages {
        stage('Checkout') {
            steps {
                git(
                        branch: "${SOURCE_BRANCH}",
                        url: "${REMOTE}",
                        credentialsId: 'ssh'
                )
            }
        }

        stage('Update compose version') {

            parallel {
                stage('Dev') {
                    when {
                        expression {
                            params.Dev
                        }
                    }

                    steps {
                        script {
                            updateComposeFile('dev-docker-compose.yaml', Image, Tag)
                        }
                    }
                }

                stage('Ref') {
                    when {
                        expression {
                            params.reference
                        }
                    }

                    steps {
                        script {
                            updateComposeFile('ref-docker-compose.yaml', Image, Tag)
                        }
                    }
                }

                stage('Int') {
                    when {
                        expression {
                            params.Int
                        }
                    }

                    steps {
                        script {
                            updateComposeFile('int-docker-compose.yaml', Image, Tag)
                        }
                    }
                }

                stage('Ops') {
                    when {
                        expression {
                            params.Ops
                        }
                    }

                    steps {
                        script {
                            updateComposeFile('ops-docker-compose.yaml', Image, Tag)
                        }
                    }
                }

            }
        }

        stage('Push Updates') {
            steps {
                script {
                    sshagent(credentials: ['ssh']) {
                        sh "git push origin master"
                    }
                }
            }
        }

        stage('Deploy') {

            parallel {
                stage('Dev') {
                    when {
                        expression {
                            params.Dev
                        }
                    }

                    agent {
                        label params.Dev
                    }

                    steps {

                        git(
                                branch: "${SOURCE_BRANCH}",
                                url: "${REMOTE}",
                                credentialsId: 'ssh'
                        )

                        script {
                            sh 'docker-compose -d -f dev-docker-compose.yaml up'
                        }
                    }
                }

                stage('Ref') {
                    when {
                        expression {
                            params.Ref
                        }
                    }

                    agent {
                        label params.Ref
                    }

                    steps {

                        git(
                                branch: "${SOURCE_BRANCH}",
                                url: "${REMOTE}",
                                credentialsId: 'ssh'
                        )

                        script {
                            sh 'docker-compose -d -f ref-docker-compose.yaml up'
                        }
                    }
                }

                stage('Int') {
                    when {
                        expression {
                            params.Int
                        }
                    }

                    agent {
                        label params.Int
                    }

                    steps {

                        git(
                                branch: "${SOURCE_BRANCH}",
                                url: "${REMOTE}",
                                credentialsId: 'ssh'
                        )

                        script {
                            sh 'docker-compose -d -f int-docker-compose.yaml up'
                        }
                    }
                }

                stage('Ops') {
                    when {
                        expression {
                            params.Ops
                        }
                    }

                    agent {
                        label params.Ops
                    }

                    steps {

                        git(
                                branch: "${SOURCE_BRANCH}",
                                url: "${REMOTE}",
                                credentialsId: 'ssh'
                        )

                        script {
                            sh 'docker-compose -d -f ops-docker-compose.yaml up'
                        }
                    }
                }

            }
        }
    }
}

def updateComposeFile(file, image, tag) {
    sshagent(credentials: ['ssh']) {
        sh """
                    IMAGE=\$(echo ${image} | sed 's/\\//\\\\\\//g')
                    COMPOSE_FILE=${file}
                    TAG=${tag}
            
                    sed -i -E "s/\$IMAGE:.+/\$IMAGE:\$TAG/" \$COMPOSE_FILE
                    
                    if [ \$(git diff | wc -l) -gt 0 ]; then
                        git add ${file}
                        git commit -m "[Automated commit: version bump]"
                    fi
    
                """
    }
}
