#!/bin/bash
set -e

echo "=== Configurando Java 17 ==="

# instalar java 17 si no estuviera
sudo apt update
sudo apt install -y openjdk-17-jdk

# añadir a .bashrc solo si no existe
grep -q "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64" ~/.bashrc || {
  echo '' >> ~/.bashrc
  echo '# Java 17' >> ~/.bashrc
  echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
  echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
}

echo "=== Configurando Android SDK ==="

grep -q "ANDROID_HOME=\$HOME/android-sdk" ~/.bashrc || {
  echo '' >> ~/.bashrc
  echo '# Android SDK' >> ~/.bashrc
  echo 'export ANDROID_HOME=$HOME/android-sdk' >> ~/.bashrc
  echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH' >> ~/.bashrc
  echo 'export PATH=$ANDROID_HOME/platform-tools:$PATH' >> ~/.bashrc
}

# recargar entorno
source ~/.bashrc

echo "JAVA:"
java --version

mkdir -p $ANDROID_HOME
cd $ANDROID_HOME

if [ ! -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    wget https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
    unzip -o commandlinetools-linux-13114758_latest.zip

    mkdir -p cmdline-tools/latest

    mv cmdline-tools/bin cmdline-tools/latest/ || true
    mv cmdline-tools/lib cmdline-tools/latest/ || true
    mv cmdline-tools/NOTICE.txt cmdline-tools/latest/ || true
    mv cmdline-tools/source.properties cmdline-tools/latest/ || true
fi

yes | sdkmanager --licenses

sdkmanager \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"

echo
echo "Todo listo."
echo "Prueba:"
echo "cd /workspaces/pintometeo/pintometeo"
echo "./gradlew assembleDebug"
