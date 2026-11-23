FROM gcr.io/distroless/static-debian12:latest
ARG TARGETPLATFORM
COPY dist/docker/${TARGETPLATFORM}/multilane-cli /usr/bin/multilane-cli
ENV MULTILANE_STATE_DIR=/data
VOLUME /data
WORKDIR /data
ENTRYPOINT ["/usr/bin/multilane-cli"]
CMD ["run"]
