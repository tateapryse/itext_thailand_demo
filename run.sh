#!/usr/bin/env bash
#
# Interactive launcher for the iText Shenzhen bank-statement demo.
# Builds the fat-jar (first run only) and runs the interactive workflow.
#
set -euo pipefail

cd "$(dirname "$0")"

JAR="target/itext-shenzhen-demo.jar"

if [[ ! -f "$JAR" ]]; then
  echo "==> Building the demo (first run downloads iText dependencies)..."
  mvn -q -DskipTests package
fi

echo "==> Starting the interactive demo..."
exec java -jar "$JAR"
