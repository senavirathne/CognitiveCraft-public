ARG C_RUNTIME=cognitivecraft-e2e:java25
FROM ${C_RUNTIME}
USER root
# Use the existing E2E loader/JDK/graphics stack; add only harness command prerequisites.
RUN apt-get update && apt-get install -y --no-install-recommends git unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work/source
ENTRYPOINT ["python3", "e2e/parallel_c/ci.py"]
