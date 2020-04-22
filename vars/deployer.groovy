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
            git(
                    branch: "${env.branch}",
                    url: "${env.repo}",
                    credentialsId: 'ssh'
            )
        }

        stage('Update compose version') {

            parallel {
                stage('Deploy to Dev') {
                    when {
                        Dev
                    }

                    steps {
                        script {
                            updateComposeFile('dev-docker-compose.yaml', Image, Tag)
                        }
                    }
                }

                stage('Deploy to Ref') {
                    when {
                        Ref
                    }

                    steps {
                        script {
                            updateComposeFile('ref-docker-compose.yaml', Image, Tag)
                        }
                    }
                }

                stage('Deploy to Int') {
                    when {
                        Int
                    }

                    steps {
                        script {
                            updateComposeFile('int-docker-compose.yaml', Image, Tag)
                        }
                    }
                }

                stage('Deploy to Ops') {
                    when {
                        Ops
                    }

                    steps {
                        script {
                            updateComposeFile('ops-docker-compose.yaml', Image, Tag)
                        }
                    }
                }

            }
        }

        stage('Push File') {
            steps {
                script {
                    sshagent(credentials: ['ssh']) {
                        sh "git push origin ${SOURCE_BRANCH}"
                    }
                }
            }
        }

        stage('Deploy') {
            agent {
                label "${env.environment}"
            }

            steps {
                echo "hello"
                //sh 'docker-compose -f docker-compose.yaml up -d'
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
                        git add docker-compose.yaml
                        git commit -m "[Automated commit: version bump]"
                    fi
    
                """
    }
}
