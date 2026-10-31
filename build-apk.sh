#!/usr/bin/env bash
# Gera o APK do Matilha sem o Android Gradle Plugin:
# aapt (recursos + R.java) -> Gradle/Kotlin (classes) -> dx (DEX) -> aapt (pacote) -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
# Código compila contra API 35 (Gradle). Recursos são ligados contra API 33: o aapt (v1) do Ubuntu
# não lê o resources.arsc compactado da API 35; os IDs de atributos são estáveis entre versões.
AJAR="$SDK/platforms/android-33/android.jar"
APP=app
OUT=$APP/build/apk
GEN=$APP/build/generated/r
rm -rf "$OUT"; mkdir -p "$OUT"

echo "1/6 recursos (aapt, via Gradle)"

echo "2/6 compilando Kotlin (Gradle)"
gradle :$APP:jar :$APP:collectRuntimeJars --console=plain -q --offline ${GRADLE_ARGS:-} 2>&1 | grep -v "Picked up" || true
test -f $APP/build/libs/$APP.jar

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

echo "6/6 assinando"
KS="${MATILHA_KEYSTORE:-$HOME/.android/matilha-debug.keystore}"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias matilha \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Matilha Debug,O=Matilha,C=BR" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias matilha \
  --out "$OUT/matilha.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/matilha.apk"
mkdir -p dist && cp "$OUT/matilha.apk" dist/matilha.apk
ls -la dist/matilha.apk
