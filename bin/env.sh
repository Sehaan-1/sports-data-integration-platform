#!/usr/bin/env bash
# SDIP build environment. Source this before running maven:  source bin/env.sh
# Uses the local toolchain under ~/.cache if present (created by the setup script),
# otherwise falls back to whatever is on PATH.
TOOLS="$HOME/.cache/toolchain"
if [ -d "$TOOLS/jdk21" ]; then
  export JAVA_HOME="$TOOLS/jdk21"
fi
if [ -d "$TOOLS/maven" ]; then
  export PATH="$TOOLS/maven/bin:$JAVA_HOME/bin:$PATH"
else
  export PATH="$JAVA_HOME/bin:$PATH"
fi
# Keep the local repository outside the workspace snapshot.
export MAVEN_ARGS="-Dmaven.repo.local=$HOME/.cache/m2"
export MAVEN_OPTS="-Xmx640m"
echo "JAVA_HOME=$JAVA_HOME"
java -version 2>&1 | head -1
mvn -version 2>/dev/null | head -1 || echo "maven not found"
