def version = "${env.BUILD_NUMBER}"

node('docker0') {
    stage 'Checkout'
    git credentialsId: 'bitbucket', url: 'http://user@bitbucket:7990/scm/test/jenkins-demo-project.git'

    stage 'Build jar'
    sh "./gradlew -Pversion=${version} build"

    stage 'QA'
    withSonarQubeEnv('sonar') {
        sh "./gradlew -Pversion=${version} --info sonarqube"
    }

    sleep 10

    def qg = waitForQualityGate()
    if (qg.status != 'OK') {
      error "Pipeline aborted due to quality gate failure: ${qg.status}"
    }

    stage 'Publish jar'
    nexusArtifactUploader artifacts: [
        [artifactId: 'demo',
        classifier: 'jar',
        file: "build/libs/demo-${version}.jar",
        type: 'jar']
    ], credentialsId: 'bitbucket', groupId: 'com.example',
    nexusUrl: 'nexus:8081', nexusVersion: 'nexus3',
    protocol: 'http', repository: 'maven-releases',
    version: "${version}"

    stage 'Publish docker'
    sh "curl -o app.jar http://nexus:8081/repository/maven-releases/com/example/demo/${version}/demo-${version}-jar.jar"
    docker.withServer('tcp://socatdockersock:2375') {
       docker.withRegistry('http://nexus:20000', 'bitbucket') {
            docker.build("demo").push("${version}")
       }
    }
}

node('docker0') {
  stage 'Deploy'
  docker.withServer('tcp://socatdockersock:2375') {
    sh """docker run --net jenkins0_default \
    --name demo${version} -d -p 10080 nexus:20000/demo:${version}"""
  }

  stage 'Post-deploy check'
  def checkCommand = createCheckCommand(version)
  waitUntil {
    try {
      sh "${checkCommand}"
      true
    } catch(error) {
      sleep 10
      currentBuild.result = 'SUCCESS'
      false
    }
  }

  stage 'Finalize'
  docker.withServer('tcp://socatdockersock:2375') {
    sh "docker rm -f demo${version}"
    sh "docker rmi -f nexus:20000/demo:${version}"
  }
}

def createCheckCommand(version) {
  return "curl http://demo${version}:10080/health"
}
