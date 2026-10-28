ARG CC_E2E_IMAGE=cognitivecraft-e2e:java25
FROM ${CC_E2E_IMAGE}
# Extend the established runtime only with Git for immutable source/evidence checks.
USER root
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
