FROM jenkins/jenkins:lts-jdk21

USER root

RUN apt-get update \
    && apt-get install -y docker.io \
    && rm -rf /var/lib/apt/lists/*

ARG DOCKER_GID=984

RUN groupmod -g ${DOCKER_GID} docker \
    && usermod -aG docker jenkins

USER jenkins