FROM ubuntu:jammy AS base

ARG TZ=Etc/UTC
ARG UID=1000
ARG GID=1000
ARG USERNAME=runner_user

ENV TZ=${TZ} \
    DEBIAN_FRONTEND=noninteractive \
    USERNAME=${USERNAME}

RUN ln -snf /usr/share/zoneinfo/${TZ} /etc/localtime && echo ${TZ} > /etc/timezone

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      gosu \
      jq \
      openjdk-11-jre-headless \
      sudo \
      unzip \
      zip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN groupadd --gid ${GID} ${USERNAME} && \
    useradd --create-home --uid ${UID} --gid ${GID} --shell /bin/bash ${USERNAME} && \
    usermod -aG sudo ${USERNAME} && \
    mkdir -p /etc/sudoers.d && \
    echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USERNAME} && \
    chmod 0440 /etc/sudoers.d/${USERNAME}

FROM base AS build

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      maven \
      openjdk-11-jdk-headless \
      wget && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

USER ${USERNAME}
WORKDIR /home/${USERNAME}

RUN wget -q https://public.dhe.ibm.com/ibmdl/export/pub/software/openliberty/runtime/release/23.0.0.8/openliberty-javaee8-23.0.0.8.zip && \
    unzip -q openliberty-javaee8-23.0.0.8.zip && \
    rm openliberty-javaee8-23.0.0.8.zip

COPY --chown=${USERNAME}:${USERNAME} download-lib.sh /home/${USERNAME}/MapService/download-lib.sh
COPY --chown=${USERNAME}:${USERNAME} MapService /home/${USERNAME}/MapService/MapService

WORKDIR /home/${USERNAME}/MapService
RUN if [ ! -f MapService/WebContent/jquery/jquery-1.11.3.min.js ] || \
       [ ! -f MapService/WebContent/openlayers/v4.6.5/ol.js ] || \
       [ ! -f MapService/WebContent/js/lib/qrcode.js ]; then \
      sh download-lib.sh; \
    fi

RUN cd MapService && mvn -q package

FROM base AS final

USER ${USERNAME}
COPY --from=build --chown=${USERNAME}:${USERNAME} /home/${USERNAME}/wlp /home/${USERNAME}/wlp
WORKDIR /home/${USERNAME}/wlp/bin
RUN ./server create defaultServer

COPY --from=build --chown=${USERNAME}:${USERNAME} /home/${USERNAME}/MapService/MapService/target/MapService-0.0.1-SNAPSHOT.war /home/${USERNAME}/wlp/usr/servers/defaultServer/apps/
COPY --chown=${USERNAME}:${USERNAME} docker/server/server.xml /home/${USERNAME}/wlp/usr/servers/defaultServer/
COPY --chown=${USERNAME}:${USERNAME} docker/server/server-data.sh /home/${USERNAME}/server-data.sh
COPY --chown=${USERNAME}:${USERNAME} docker/server/server-init.sh /home/${USERNAME}/server-init.sh

USER root
COPY docker/server/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh /home/${USERNAME}/server-data.sh /home/${USERNAME}/server-init.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["./server", "run", "defaultServer"]
