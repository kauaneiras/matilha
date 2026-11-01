#!/usr/bin/env bash
# Gera o APK do Matilha sem o Android Gradle Plugin:
# aapt (recursos + R.java) -> Gradle/Kotlin (classes) -> dx (DEX) -> aapt (pacote) -> zipalign -> apksigner.
#
# Variáveis (todas opcionais; os padrões são GENÉRICOS, só para desenvolvimento):
#   GRADLE                   comando do Gradle (padrão: ./gradlew se existir, senão gradle)
#   GRADLE_ARGS              argumentos extras (ex.: --offline)
#   ANDROID_HOME             SDK com platforms/android-{33,35}/android.jar (padrão: /opt/android-sdk)
#   MATILHA_KEYSTORE         arquivo da keystore (criado se não existir)
#   MATILHA_KEYSTORE_PASSWORD, MATILHA_KEY_ALIAS, MATILHA_KEY_PASSWORD, MATILHA_KEY_DNAME
#   Para produção: use uma keystore própria, guardada fora do repositório, e senhas fortes.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
# Código compila contra API 35 (Gradle). Recursos são ligados contra API 33: o aapt (v1) do Ubuntu
# não lê o resources.arsc compactado da API 35; os IDs de atributos são estáveis entre versões.
for api in 33 35; do
  [ -f "$SDK/platforms/android-$api/android.jar" ] || { echo "ERRO: falta $SDK/platforms/android-$api/android.jar"; exit 1; }
done
if [ -z "${GRADLE:-}" ]; then [ -x ./gradlew ] && GRADLE=./gradlew || GRADLE=gradle; fi

export MATILHA_KEYSTORE="${MATILHA_KEYSTORE:-$HOME/.android/matilha-debug.keystore}"
export MATILHA_KEYSTORE_PASSWORD="${MATILHA_KEYSTORE_PASSWORD:-matilha-dev-store}"
export MATILHA_KEY_ALIAS="${MATILHA_KEY_ALIAS:-matilha}"
# PKCS12 exige a mesma senha para a keystore e para a chave.
export MATILHA_KEY_PASSWORD="${MATILHA_KEY_PASSWORD:-$MATILHA_KEYSTORE_PASSWORD}"
MATILHA_KEY_DNAME="${MATILHA_KEY_DNAME:-CN=Matilha Dev,O=Matilha,C=BR}"

APP=app
OUT=$APP/build/apk
rm -rf "$OUT" "$APP/build/libs"; mkdir -p "$OUT"

echo "1/6 recursos + 2/6 Kotlin (Gradle: $GRADLE)"
set +e
$GRADLE :$APP:jar :$APP:collectRuntimeJars --console=plain -q ${GRADLE_ARGS:-} 2>&1 | grep -v "Picked up"
status=${PIPESTATUS[0]}
set -e
if [ "$status" != 0 ] || [ ! -f "$APP/build/libs/$APP.jar" ]; then echo "ERRO: compilação falhou"; exit 1; fi

echo "3/6 DEX (dx)"
mkdir -p "$OUT/jars"
cp $APP/build/libs/$APP.jar "$OUT/jars/"
for j in $APP/build/runtime-jars/*.jar; do
  cp "$j" "$OUT/jars/"
  zip -q -d "$OUT/jars/$(basename "$j")" 'META-INF/versions/*' 'module-info.class' >/dev/null 2>&1 || true
done
echo "   verificando invokedynamic (não suportado pelo ART sem desugaring)"
python3 tools-check-indy.py "$OUT/jars/$APP.jar" $(ls "$OUT"/jars/*.jar | grep -v "/$APP.jar$")
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT"/jars/*.jar
python3 tools-check-dex.py $APP/src/main/AndroidManifest.xml "$OUT/classes.dex"

echo "4/6 empacotando"
cp $APP/build/res/resources.ap_ "$OUT/unsigned.apk"
(cd "$OUT" && aapt add -f unsigned.apk classes.dex >/dev/null)

echo "5/6 zipalign"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "6/6 assinando (keystore: $MATILHA_KEYSTORE, alias: $MATILHA_KEY_ALIAS)"
if [ ! -f "$MATILHA_KEYSTORE" ]; then
  echo "   keystore não existe: criando uma nova (chaves de DESENVOLVIMENTO)"
  mkdir -p "$(dirname "$MATILHA_KEYSTORE")"
  keytool -genkeypair -storetype PKCS12 -keystore "$MATILHA_KEYSTORE" \
    -storepass:env MATILHA_KEYSTORE_PASSWORD -keypass:env MATILHA_KEY_PASSWORD -alias "$MATILHA_KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "$MATILHA_KEY_DNAME" >/dev/null 2>&1
fi
apksigner sign --ks "$MATILHA_KEYSTORE" --ks-pass env:MATILHA_KEYSTORE_PASSWORD --key-pass env:MATILHA_KEY_PASSWORD \
  --ks-key-alias "$MATILHA_KEY_ALIAS" --out "$OUT/matilha.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/matilha.apk"
mkdir -p dist && cp "$OUT/matilha.apk" dist/matilha.apk
ls -la dist/matilha.apk
