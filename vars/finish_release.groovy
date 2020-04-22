pipeline {
    agent any

    parameters {
        string(
            name: 'Label', 
            defaultValue: 'DEFAULT', 
            description: 'This is unique name which will be prefixed on the end of the branch e.g. release/release-mylabel'
        )
    }

    environment {
        SOURCE_BRANCH = 'develop'
    }

    stage('Clean') {
        cleanWs()
    }

    stage('Obtain approval') {
        timeout(time: 5, unit: 'MINUTES') { 
            input(
                    message: "Please provide approval for release to start",
                    ok: 'Submit',
                    parameters: [
                            booleanParam(
                                    defaultValue: false,
                                    description: 'This approves that a release can start',
                                    name: 'Approved'
                            )
                    ]
                    submitter: 'john'
        )
        ])
    }

    stage('Finish Release') {
        git(
            branch: "${env.Branch}",
            url: "git@github.com:dan05011991/demo-application-backend.git",
            credentialsId: 'ssh'
        )

        sh """
            git checkout master
            git pull origin master
            git merge release/release-${env.Label}
            git push origin master'
            
            git checkout develop
            git pull origin develop
            git merge release/release-${env.Label}
            git push origin develop
        """
    }
}



node {

    
    if(env.Release == 'Start') {
        sh "git checkout -b release/release-${env.Label}"
        sh "git push origin release/release-${env.Label}"
    } else if(env.Release =='Finish') {
        sh """
            git checkout master
            git pull origin master
            git merge release/release-${env.Label}
            git push origin master'
            
            git checkout develop
            git pull origin develop
            git merge release/release-${env.Label}
            git push origin develop
        """
    }
}