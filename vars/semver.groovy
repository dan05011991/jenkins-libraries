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

    versionFileName = getVersionFileName(PROJECT_KEY, RELEASE_TYPE)
    println versionFileName

    updateVersionFile(PROJECT_KEY, RELEASE_TYPE, GIT_TAG)
    sh("rm semver.sh")

    sh("git add ${versionFileName}")
    sh("git commit -m \"Bumped version for ${PROJECT_KEY}\"")
    sh("git push origin master")

    sh("cat ${versionFileName} > version")
    archiveArtifacts artifacts: 'version', fingerprint: true
}

def updateVersionFile(key, type, tag) {
    versionFileName = getVersionFileName(key, type)
    savedVersion = getSavedVersion(key, type, tag)

    if(type != 'p') {
        sh("echo \"\$(./semver.sh -${type} ${savedVersion})\" > ${versionFileName}")
        return
    }

    nonPatchOpsVersion = removePatchVersion(tag)
    nonPatchSavedVersion = removePatchVersion(savedVersion)

    if(nonPatchOpsVersion == nonPatchSavedVersion) {
        sh("echo \"\$(./semver.sh -${type} ${savedVersion})\" > ${versionFileName}")
    } else {
        sh("echo \"\$(./semver.sh -${type} ${nonPatchOpsVersion}.1)\" > ${versionFileName}")
    }
}

def removePatchVersion(tag) {
    assert tag.length() > 0

    println tag
    
    majorMinorTag = tag.substring(0, tag.lastIndexOf("."))
    assert majorMinorTag.length() > 0

    println majorMinorTag
    return majorMinorTag 
}

def getSavedVersion(key, type, tag) {
    key = getVersionFileName(key, type)

    savedVersion = sh(
        script: """
            if [ ! -f ${key} ]; then
                echo "${tag}" > ${key}
            fi
            cat ${key}
        """, 
        returnStdout: true)
        .trim()
    assert savedVersion.length() > 0

    return savedVersion
}

def getVersionFileName(key, type) {
    if(type == 'p') {
        key = key + '-p'
    }
    return key + ".version"
}
def createScript(scriptName) {
    def scriptContent = libraryResource "com/corp/pipeline/scripts/${scriptName}"
    writeFile file: "${scriptName}", text: scriptContent
    sh "chmod +x ${scriptName}"
}