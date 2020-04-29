class customPipeline {
    def stage(name, execute, block) {
        return stage(name, execute ? block : {
            echo "skipped stage $name"
            Utils.markStageSkippedForConditional(name)
        })
    }

    def step(name, block) {
        return step(name, true, block)  
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
}

