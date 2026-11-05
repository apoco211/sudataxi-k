#!/usr/bin/env sh
# Stub gradlew - will use system gradle if wrapper jar missing
set -e
if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar gradle/wrapper/gradle-wrapper.jar "$@"
else
  echo "Wrapper jar missing, using system gradle"
  exec gradle "$@"
fi
