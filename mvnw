#!/bin/sh
# Minimal Maven bootstrap: downloads Maven once into ~/.m2/wrapper and runs it.
set -e
MVN_VER=3.9.9
MVN_BASE="$HOME/.m2/wrapper/dists/apache-maven-$MVN_VER"
MVN_BIN="$MVN_BASE/apache-maven-$MVN_VER/bin/mvn"
if [ ! -x "$MVN_BIN" ]; then
  echo "Downloading Maven $MVN_VER ..."
  mkdir -p "$MVN_BASE"
  curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MVN_VER/apache-maven-$MVN_VER-bin.tar.gz" \
    | tar -xz -C "$MVN_BASE"
fi
exec "$MVN_BIN" "$@"
