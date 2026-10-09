FROM eclipse-temurin:25-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 xvfb x11-xkb-utils libgl1-mesa-dri libglx-mesa0 libasound2t64 \
    libxrandr2 libxcursor1 libxinerama1 libxi6 libxtst6 libxfont2 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /repo
ENV LIBGL_ALWAYS_SOFTWARE=1
ENTRYPOINT ["python3", "e2e/launch.py"]
