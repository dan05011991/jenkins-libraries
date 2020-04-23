@Library('Pipelines') _

import java.util.regex.Pattern

pipeline {
    agent any

    parameters {
        string(
            name: 'PROJECT_KEY', 
            defaultValue: 'DEFAULT'
        )
        string(
            name: 'RELEASE_TYPE', 
            defaultValue: 'DEFAULT'
        )
        string(
            name: 'GIT_TAG', 
            defaultValue: 'DEFAULT'
        )
    }

    stage('Clean') {
        steps {
            script {
                cleanWs()
                createScript('semver.sh')
            }
        }
    }

    stage('Checkout') {
        steps {
            script {
                git(
                        branch: "master",
                        url: "git@github.com:dan05011991/versioning.git",
                        credentialsId: 'ssh'
                )
            }
        }
    }

    stage('Get Version') {
        steps {
            script {
                if (RELEASE_TYPE != 'M' && RELEASE_TYPE != 'm' && RELEASE_TYPE != 'p') {
                    throw new Exception('Incorrect use of the release type flag')
                }

                def pattern = GIT_TAG =~ /((?:[0-9]+\.)+)(?:[0-9]+)/
                assert matcher.find() 
                assert matcher.size() == 1
                assert matcher[0..-1] == ["groovier", "better"] 

                sh """
                    if [ ! -f ${PROJECT_KEY} ]; then
                        echo "1.0.0" > ${PROJECT_KEY}
                    else
                        echo "\$(./semver.sh -${RELEASE_TYPE} \$(cat ${PROJECT_KEY}))" > ${PROJECT_KEY}
                    fi

                    rm semver.sh

                    git add ${PROJECT_KEY}
                    
                    git commit -m "Bumped version for ${PROJECT_KEY}"
                    
                    git push origin master
                """

                sh "cat ${PROJECT_KEY} > version"

                archiveArtifacts artifacts: 'version', fingerprint: true
            }
        }
    } 
}

def createScript(scriptName) {
    def scriptContent = libraryResource "com/corp/pipeline/scripts/${scriptName}"
    writeFile file: "${scriptName}", text: scriptContent
    sh "chmod +x ${scriptName}"
}