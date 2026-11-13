pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15'))
        timeout(time: 90, unit: 'MINUTES')
    }

    parameters {
        booleanParam(name: 'DEPLOY', defaultValue: false, description: '构建成功后部署到本机；首次需先配置 /opt/rsdp/.env 与 HTTPS 反向代理')
    }

    environment {
        DEPLOY_ROOT = '/opt/rsdp'
        ENV_FILE = '/opt/rsdp/.env'
        COMPOSE_PARALLEL_LIMIT = '1'
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Preflight') {
            steps { sh 'bash scripts/deploy.sh check' }
        }
        stage('Migration sync') {
            steps { sh 'bash scripts/check_flyway_schema_sync.sh' }
        }
        stage('Java tests') {
            steps {
                sh '''#!/usr/bin/env bash
set -euo pipefail
test_image="rsdp-server-tests:${BUILD_NUMBER}"
rm -rf test-results/java
docker build --target test -t "$test_image" server
container=$(docker create "$test_image")
trap 'docker rm "$container" >/dev/null' EXIT
mkdir -p test-results/java
docker cp "$container:/build/target/surefire-reports/." test-results/java/
'''
            }
            post {
                always {
                    junit testResults: 'test-results/java/TEST-*.xml', allowEmptyResults: true
                }
            }
        }
        stage('Frontend tests') {
            steps {
                sh 'docker build --target test -t rsdp-web-tests:${BUILD_NUMBER} web'
            }
        }
        stage('Build images') {
            steps { sh 'bash scripts/deploy.sh build' }
        }
        stage('Deploy') {
            when { expression { params.DEPLOY } }
            steps {
                // 仅允许部署当前 origin/main
                sh '''#!/usr/bin/env bash
set -euo pipefail
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" || {
    echo '只允许部署当前 origin/main。' >&2
    exit 1
}
bash scripts/deploy.sh deploy
'''
            }
        }
    }
    post {
        success { echo '构建通过；部署结果请查看 Deploy 阶段。配置文件保留在服务器，不进入镜像或构建归档。' }
        failure { echo '流水线失败。查看失败阶段；部署备份位于 /opt/rsdp/backups。' }
    }
}
