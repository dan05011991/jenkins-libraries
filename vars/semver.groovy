node {
    cleanWs()
    
    git(
            branch: "master",
            url: "git@github.com:dan05011991/versioning.git",
            credentialsId: 'ssh'
    )
    
    createScript('semver.sh')
    
    sh """
        if [ ! -f ${PROJECT_KEY} ]; then
            echo "1.0.0" > ${PROJECT_KEY}
        else
            echo "\$(./semver.sh -${RELEASE_TYPE} \$(cat ${PROJECT_KEY}))" > ${PROJECT_KEY}
        fi

        git add ${PROJECT_KEY}
        
        git commit -m "Bumped version for ${PROJECT_KEY}"
        
        git push origin master
    """

    archiveArtifacts artifacts: 'version', fingerprint: true
}

def createScript(scriptName) {
    def scriptContent = libraryResource "com/corp/pipeline/scripts/${scriptName}"
    writeFile file: "${scriptName}", text: scriptContent
    sh "chmod +x ${scriptName}"
}