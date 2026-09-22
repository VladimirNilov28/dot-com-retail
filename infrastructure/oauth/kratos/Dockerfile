FROM oryd/kratos:v26.2.0

USER root

WORKDIR /etc/kratos

COPY kratos.yml /etc/kratos/kratos.yml
COPY identity.schema.json /etc/kratos/identity.schema.json

USER 1000

EXPOSE 4433 4434

ENTRYPOINT ["kratos"]
CMD ["serve", "--dev", "--config", "/etc/kratos/kratos.yml"]
