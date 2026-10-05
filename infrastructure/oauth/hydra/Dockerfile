FROM oryd/hydra:v26.2.0

USER root

WORKDIR /etc/hydra

COPY hydra.yml /etc/hydra/hydra.yml

USER 1000

EXPOSE 4444 4445

ENTRYPOINT ["hydra"]
CMD ["serve", "all", "--dev" ,"--config", "/etc/hydra/hydra.yml"]

# TODO Remove "--dev" flag