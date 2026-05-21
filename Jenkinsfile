pipeline {
    agent any

    environment {
        IMAGE_NAME = 'video-stream-middleware'
        IMAGE_TAG = "${env.BUILD_NUMBER}"
        CONTAINER_NAME = 'video-stream-middleware'
        HOST_PORT = '4900'
        CONTAINER_PORT = '4900'
        NETTY_PORT = '4901'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        timestamps()
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Docker Build') {
            steps {
                script {
                    sh "DOCKER_BUILDKIT=1 docker build -t ${IMAGE_NAME}:${IMAGE_TAG} -t ${IMAGE_NAME}:latest ."
                }
            }
        }

        stage('Deploy') {
            steps {
                script {
                    // 停止并删除旧容器（释放容器名，否则 docker run --name 会冲突）
                    sh "docker rm -f ${CONTAINER_NAME} 2>/dev/null || true"

                    // 启动新容器
                    sh """
                        docker run -d \
                            --name ${CONTAINER_NAME} \
                            --restart unless-stopped \
                            -p ${HOST_PORT}:${CONTAINER_PORT} \
                            -p ${NETTY_PORT}:${NETTY_PORT} \
                            -e SERVER_PORT=${CONTAINER_PORT} \
                            -e NETTY_PORT=${NETTY_PORT} \
                            -e SPRING_PROFILES_ACTIVE=prod \
                            -e TZ=Asia/Shanghai \
                            -e JAVA_OPTS="-Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -XX:MaxDirectMemorySize=1g -XX:+HeapDumpOnOutOfMemoryError" \
                            -v /app/logs:/app/logs \
                            ${IMAGE_NAME}:${IMAGE_TAG}
                    """

                    echo "部署完成: ${IMAGE_NAME}:${IMAGE_TAG}"

                    // 清理旧镜像，只保留最近3个版本
                    sh '''
                        FMT='{{.Tag}}'
                        docker images video-stream-middleware --format "$FMT" \
                            | grep -E '^[0-9]+$' \
                            | sort -rn \
                            | tail -n +4 \
                            | xargs -r -I {} docker rmi video-stream-middleware:{} 2>/dev/null || true
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "流水线成功: ${IMAGE_NAME}:${IMAGE_TAG} 已部署"
        }
        failure {
            echo "流水线失败: 部署已回滚或清理完成"
        }
        always {
            sh 'docker image prune -f 2>/dev/null || true'
            cleanWs()
        }
    }
}
