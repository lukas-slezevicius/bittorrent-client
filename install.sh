#!/usr/bin/sh
mkdir -p "$HOME/.local/bin"
mkdir -p "$HOME/.local/share/Sembucha"
mkdir -p "$HOME/.local/share/Sembucha/Torrents"
touch "$HOME/.local/share/Sembucha/torrents.properties"
cp sembucha.py "$HOME/.local/bin/sembucha"
chmod +x "$HOME/.local/bin/sembucha"
mvn package
cp "target/sembucha-0.1-jar-with-dependencies.jar" "$HOME/.local/share/Sembucha/sembucha-0.1-jar-with-dependencies.jar"
