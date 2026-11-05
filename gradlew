#!/usr/bin/env sh
DIR="$(cd "$(dirname "$0")" >/dev/null && pwd)"
GRADLE_WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
  echo "gradle-wrapper.jar missing - run 'gradle wrapper --gradle-version 8.4' once (with system gradle) to generate it, or let CI do it."
fi
exec java -jar "$GRADLE_WRAPPER_JAR" "$@"
