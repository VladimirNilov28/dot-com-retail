FROM jenkins/jenkins:lts-jdk21

USER root

RUN apt-get update \
    && apt-get install -y \
        curl \
        docker.io \
        git \
        python3 \
        python3-pip \
        python3-venv \
    && rm -rf /var/lib/apt/lists/*


ARG DOCKER_GID=984

RUN groupmod -g "${DOCKER_GID}" docker \
    && usermod -aG docker jenkins


RUN python3 -m venv /opt/jenkins-python


COPY requirements.txt /tmp/requirements.txt


RUN /opt/jenkins-python/bin/pip install \
    --no-cache-dir \
    -r /tmp/requirements.txt


RUN chown -R jenkins:jenkins /opt/jenkins-python


USER jenkins


ENV PATH="/opt/jenkins-python/bin:${PATH}"