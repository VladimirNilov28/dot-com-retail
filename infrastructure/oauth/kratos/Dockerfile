FROM oryd/kratos:v26.2.0

USER root
RUN apk add --no-cache jq yq

WORKDIR /etc/kratos

COPY kratos.yml /etc/kratos/kratos.yml
COPY identity.schema.json /etc/kratos/identity.schema.json
COPY customer.schema.json /etc/kratos/customer.schema.json
COPY registration-hook.jsonnet /etc/kratos/registration-hook.jsonnet
COPY registration-bind.jsonnet /etc/kratos/registration-bind.jsonnet
COPY --chmod=755 entrypoint.sh /etc/kratos/entrypoint.sh

USER 1000

EXPOSE 4433 4434

ENTRYPOINT ["/etc/kratos/entrypoint.sh"]
CMD ["serve", "--dev", "--watch-courier", "--config", "/etc/kratos/kratos.yml"]
