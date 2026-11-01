# Matilha — ambiente de build/testes do app Android, sem precisar instalar nada além do Docker.
#   docker compose up            -> compila o APK e serve em http://localhost:8080
#   docker compose run --rm test -> roda os 72 testes (gera capturas em ./screens)
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle \
    LANG=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates openjdk-21-jdk-headless aapt dalvik-exchange zipalign apksigner \
      python3 curl unzip zip \
 && rm -rf /var/lib/apt/lists/*

# Opcional: CA extra para redes corporativas com proxy TLS (build: --secret id=extra_ca,src=arquivo.crt).
# Instalado depois do JDK para entrar também no keystore do Java (usado pelo Gradle).
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then \
      awk '/BEGIN CERTIFICATE/{n++} {print > ("/usr/local/share/ca-certificates/extra_ca_" n ".crt")}' /run/secrets/extra_ca \
      && update-ca-certificates -f; \
    fi

# android.jar das plataformas usadas: 35 (compilação), 33 (ligação de recursos), 26 (checagem de minSdk).
# Conferidos por SHA-256.
RUN set -eu; \
    for spec in \
      "35 4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b" \
      "33 4fade8d5e04130bd8ccd74d5fec6c86b01ad75c3ee877ccc5d660255c4c78646" \
      "26 cdc1846376a14b0370cc63454a129606b4a52cc50ada75ef0d4cf956b1ad2daa"; do \
      set -- $spec; d="$ANDROID_HOME/platforms/android-$1"; mkdir -p "$d"; \
      curl -fsSL --retry 5 --retry-delay 10 -o "$d/android.jar" "https://raw.githubusercontent.com/Sable/android-platforms/master/android-$1/android.jar"; \
      echo "$2  $d/android.jar" | sha256sum -c -; \
    done

WORKDIR /app
COPY . /app
# Garante finais de linha Unix mesmo se o repositório foi clonado no Windows com CRLF.
RUN sed -i 's/\r$//' gradlew *.sh docker/*.sh tools-*.py && chmod +x gradlew *.sh docker/*.sh

EXPOSE 8080
ENTRYPOINT ["/app/docker/entrypoint.sh"]
CMD ["serve"]
