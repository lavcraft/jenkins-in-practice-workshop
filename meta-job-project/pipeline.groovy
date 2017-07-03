pipeline {
    agent {
        label 'docker0'
    }

    parameters {
        string(name: "version",
               defaultValue: "0.${env.BUILD_NUMBER}.0",
               description: "Version of software")
    }

    stages {
        stage('Checkout') {
            steps {
                git credentialsId: 'bitbucket', url: 'http://user@bitbucket:7990/scm/test/PROJECT_NAME.git'
            }
        }

        stage('Build') {
            steps {
                sh "./gradlew -Pversion=${params.version} build"
            }
        }

        stage('QA') {
            when {
                expression {
                    withSonarQubeEnv('sonar') {
                        sh "./gradlew -Pversion=${params.version} --info sonarqube"
                    }
                    sleep 10
                    return waitForQualityGate().status != 'OK'
                }
            }
            steps {
                error "Pipeline aborted due to quality gate failure"
            }
        }

        stage('Publish JAR') {
            steps {
                nexusArtifactUploader artifacts: [
                    [artifactId: 'demo',
                    classifier: 'jar',
                    file: "build/libs/demo-${params.version}.jar",
                    type: 'jar']
                ], credentialsId: 'bitbucket', groupId: 'com.example', nexusUrl: 'nexus:8081', nexusVersion: 'nexus3', protocol: 'http', repository: 'maven-releases', version: "${params.version}"
            }
        }

        stage('Publish Docker') {
            steps {
                sh "curl -o app.jar http://nexus:8081/repository/maven-releases/com/example/demo/${params.version}/demo-${params.version}-jar.jar"
                script {
                    docker.withServer('tcp://socatdockersock:2375') {
                        docker.withRegistry('http://nexus:20000', 'bitbucket') {
                            docker.build("demo").push("${params.version}")
                        }
                    }
                }
            }
        }

        stage('Deploy') {
            steps {
                script {
                    docker.withServer('tcp://socatdockersock:2375') {
                        sh """docker run --net jenkins0_default \
                        --name demo${params.version} -d -p 10080 nexus:20000/demo:${params.version}"""
                    }
                }
            }
        }

        stage('Post-deploy check') {
            steps {
                timeout(time: 3, unit: 'MINUTES') {
                    retry(100) {
                        sleep 1
                        sh "curl http://demo${params.version}:10080/health"
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                docker.withServer('tcp://socatdockersock:2375') {
                    sh "docker rm -f demo${params.version}"
                    sh "docker rmi -f nexus:20000/demo:${params.version}"
                }
            }
        }
    }
}
