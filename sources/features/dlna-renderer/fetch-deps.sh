#!/bin/bash
# Downloads the runtime dependency jars from Maven Central into build-deps/.
# Run once before build.sh (jars are not committed to the repo).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPS_DIR="$SCRIPT_DIR/build-deps"
mkdir -p "$DEPS_DIR"
cd "$DEPS_DIR"

MAVEN="https://repo1.maven.org/maven2"
JUPNP_VERSION="3.0.4"
JETTY_VERSION="9.4.53.v20231009"
SLF4J_VERSION="2.0.11"

fetch() {
    local path="$1" file="$2"
    if [ -f "$file" ]; then
        echo "exists: $file"
        return
    fi
    echo "fetching: $file"
    curl -fsSL --max-time 300 -o "$file" "$MAVEN/$path/$file"
}

fetch "org/jupnp/org.jupnp/$JUPNP_VERSION" "org.jupnp-$JUPNP_VERSION.jar"
fetch "org/jupnp/org.jupnp.support/$JUPNP_VERSION" "org.jupnp.support-$JUPNP_VERSION.jar"
fetch "org/jupnp/org.jupnp.android/$JUPNP_VERSION" "org.jupnp.android-$JUPNP_VERSION.jar"

for artifact in jetty-server jetty-servlet jetty-client jetty-http jetty-io jetty-util jetty-security; do
    fetch "org/eclipse/jetty/$artifact/$JETTY_VERSION" "$artifact-$JETTY_VERSION.jar"
done

fetch "javax/servlet/javax.servlet-api/3.1.0" "javax.servlet-api-3.1.0.jar"
fetch "org/slf4j/slf4j-api/$SLF4J_VERSION" "slf4j-api-$SLF4J_VERSION.jar"
fetch "org/slf4j/slf4j-simple/$SLF4J_VERSION" "slf4j-simple-$SLF4J_VERSION.jar"

echo "All dependencies present in $DEPS_DIR"
