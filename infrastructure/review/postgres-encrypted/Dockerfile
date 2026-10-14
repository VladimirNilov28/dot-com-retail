# Review stack Postgres with storage-layer encryption: the data directory
# (tables, indexes, WAL, temp files) lives in a gocryptfs-encrypted volume.
# See encrypted-storage-entrypoint.sh and docs/security/encryption-at-rest.md.
FROM postgres:17
RUN apt-get update \
 && apt-get install -y --no-install-recommends gocryptfs fuse3 \
 && rm -rf /var/lib/apt/lists/* \
 && echo user_allow_other >> /etc/fuse.conf
COPY --chmod=755 encrypted-storage-entrypoint.sh /usr/local/bin/encrypted-storage-entrypoint.sh
ENTRYPOINT ["encrypted-storage-entrypoint.sh", "docker-entrypoint.sh"]
CMD ["postgres"]
