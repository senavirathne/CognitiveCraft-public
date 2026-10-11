FROM eclipse-temurin:25-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 xvfb x11-xkb-utils libgl1-mesa-dri libglx-mesa0 libegl1 libegl-mesa0 libasound2t64 \
    libxrandr2 libxcursor1 libxinerama1 libxi6 libxtst6 libxfont2 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ARG E2E_UID=1000
ARG E2E_GID=1000
# Java's private stores resolve user.name to the current numeric file owner.
RUN if ! getent group "$E2E_GID" >/dev/null; then groupadd --gid "$E2E_GID" cognitivecraft-e2e; fi \
    && if ! getent passwd "$E2E_UID" >/dev/null; then \
       useradd --uid "$E2E_UID" --gid "$E2E_GID" --no-create-home --shell /usr/sbin/nologin cognitivecraft-e2e; fi
WORKDIR /repo
ENV LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_DRIVER=offscreen
ENTRYPOINT ["python3", "e2e/launch.py"]
