import org.jenkinsci.plugins.pipeline.modeldefinition.Utils




def isRefBuild() {
    return BRANCH_NAME == 'develop'
}

def isOpsBuild() {
    return BRANCH_NAME == 'master'
}

def customStage(name, execute, block) {
    return stage(name, execute ? block : {
        echo "skipped stage $name"
        Utils.markStageSkippedForConditional(name)
    })
}

def step(name, execute, block) {
    return [
        'name': name,
        'value': execute ? block : {
            echo "skipped stage $name"
            Utils.markStageSkippedForConditional(name)
        }
    ]
}

def customParallel(steps) {
    def map = [:]

    steps.each { step ->
        map.put(step.name, step.value)
    }

    parallel(map)
}

def call(Map pipelineParams) {

    node {
        properties([
            disableConcurrentBuilds()
        ])

        stage('Pipeline setup') {
            customParallel([
                step(
                'Checkout Project', false, {
                    echo "STAGE 1"
                }),
                step('Checkout Deployment', true, {
                    echo "STAGE 2"
                }),
                step('Create pipeline scripts', true, {
                    echo "STAGE 3"
                })
            ])
        }
    }
}

