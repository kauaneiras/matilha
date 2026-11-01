#!/usr/bin/env bash
# Comandos: serve (padrão) | apk | test | shell
set -euo pipefail
cd /app
cmd="${1:-serve}"

build_apk() {
  ./build-apk.sh
  if [ -d /out ]; then cp dist/matilha.apk /out/matilha.apk && echo "APK copiado para ./dist/matilha.apk"; fi
}

case "$cmd" in
  apk)
    build_apk ;;
  serve)
    build_apk
    port="${PORT:-8080}"
    echo ""
    echo "=============================================================="
    echo " APK pronto. Baixe em:  http://localhost:$port/matilha.apk"
    echo " No celular (mesma rede Wi-Fi): http://<IP-do-seu-PC>:$port/matilha.apk"
    echo " Chaves GENÉRICAS de desenvolvimento — troque no .env para produção."
    echo "=============================================================="
    exec python3 -m http.server "$port" --directory dist ;;
  test)
    ./fetch-robolectric.sh
    ./fetch-robolectric.sh 8.0.0_r4-robolectric-r1-i7
    ./gradlew :app:test --console=plain ${GRADLE_ARGS:-}
    if [ -d /screens ]; then cp -r screens/. /screens/ && echo "Capturas copiadas para ./screens"; fi ;;
  shell)
    exec bash ;;
  *)
    exec "$@" ;;
esac
