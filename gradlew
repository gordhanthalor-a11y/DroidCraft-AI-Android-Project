#!/bin/sh
# Minimal Gradle wrapper launcher for environments with Java installed.
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=java
fi
exec "$JAVA" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
