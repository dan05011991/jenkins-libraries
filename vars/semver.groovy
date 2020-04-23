@Library('Pipelines') _

import java.util.regex.Pattern

node {
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

    cleanWs()
    
    git(
            branch: "master",
            url: "git@github.com:dan05011991/versioning.git",
            credentialsId: 'ssh'
    )
    
    createScript('semver.sh')
    
    if (RELEASE_TYPE != 'M' && RELEASE_TYPE != 'm' && RELEASE_TYPE != 'p') {
        throw new Exception('Incorrect use of the release type flag')
    }

    updateVersionFile(PROJECT_KEY, RELEASE_TYPE, GIT_TAG)
  
    sh("git add ${PROJECT_KEY}*.version")
    sh("git commit -m \"Bumped version for ${PROJECT_KEY}\"")
    sh("git push origin master")

    sh("rm semver.sh")

    sh("cat ${PROJECT_KEY} > version")
    archiveArtifacts artifacts: 'version', fingerprint: true
}

def removePatchVersion(tag) {
    def matcher = "${tag}" =~ /((?:[0-9]+\.)+)(?:[0-9]+)/
    assert matcher.find() 
    assert matcher.size() == 1
    assert matcher[0].length != 0
    return matcher[0]
}

def updateVersionFile(key, type, tag) {
    nonPatchOpsVersion = removePatchVersion(tag)
    assert nonPatchOpsVersion.length != 0

    savedVersion = getSavedVersion(key, type)
    assert savedVersion.length != 0

    nonPatchSavedVersion = removePatchVersion(savedVersion)
    assert nonPatchSavedVersion.length != 0

    versionFileName = getVersionFileName(key, type)

    if(nonPatchOpsVersion != nonPatchSavedVersion) {
        sh("echo \"\$(./semver.sh -${type} ${tag}\" > ${versionFileName}")
    } else {
        sh("echo \"\$(./semver.sh -${type} ${tag}\" > ${versionFileName}")
    }
}

def getSavedVersion(key, type) {
    key = getVersionFileName(key, type)

    return sh(
        script: """
            if [ ! -f ${key} ]; then
                echo "1.0.0" > ${key}.version
            else
                cat ${key}.version
            fi
        """, 
        returnStdout: true)
        .trim()
}

def getVersionFileName(key, type) {
    if(type == 'p') {
        key = key + '-p'
    }
    return key
}
def createScript(scriptName) {
    def scriptContent = libraryResource "com/corp/pipeline/scripts/${scriptName}"
    writeFile file: "${scriptName}", text: scriptContent
    sh "chmod +x ${scriptName}"
}