def jobs = ["jenkins-demo-project"]
jobs.each { job ->
  pipelineJob("${job}-job") {
    def pipelineScript = readFileFromWorkspace('pipeline.groovy')
    pipelineScript = pipelineScript.replace('PROJECT_NAME', job)
    definition {
      cps {
        sandbox()
        script(pipelineScript)      
      }
    }
  }
}
