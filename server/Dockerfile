# Build from the repo root so checks/ is in the context:
#   docker build -f server/Dockerfile -t zelda-stock-server .
# The tag must match the "playwright" version in server/package.json exactly.
FROM mcr.microsoft.com/playwright:v1.64.1-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends xvfb x11vnc novnc websockify tini \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server/package.json server/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY server/src ./src
COPY --chmod=755 server/entrypoint.sh ./
COPY checks ./checks

# pwuser is uid/gid 1001 in this image; the k8s securityContext uses the same.
RUN mkdir -p /data /tmp/.X11-unix && chown pwuser:pwuser /data && chmod 1777 /tmp/.X11-unix
USER pwuser
ENV NODE_ENV=production DATA_DIR=/data CHECKS_DIR=/app/checks
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/app/entrypoint.sh"]
