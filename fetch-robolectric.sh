#!/usr/bin/env bash
# Baixa o runtime Android usado pelo Robolectric (API 34), com retomada e espera em caso de limite (HTTP 429).
set -u
D="$(cd "$(dirname "$0")" && pwd)/app/build/robolectric-deps"; mkdir -p "$D"
V="${1:-14-robolectric-10818077-i7}"
F="$D/android-all-instrumented-$V.jar"
[ -f "$F" ] && exit 0
P="org/robolectric/android-all-instrumented/$V/android-all-instrumented-$V.jar"
for i in $(seq 1 60); do
  for H in https://repo.maven.apache.org/maven2 https://repo1.maven.org/maven2; do
    curl -sS -f -L -C - -o "$F.part" "$H/$P" 2>/dev/null
    if unzip -tq "$F.part" >/dev/null 2>&1; then mv "$F.part" "$F"; echo "ok"; exit 0; fi
    # Só mantém o parcial se for um download real (evita retomar a partir de uma página de erro).
    [ "$(stat -c %s "$F.part" 2>/dev/null || echo 0)" -lt 1000000 ] && rm -f "$F.part"
  done
  sleep 60
done
exit 1
