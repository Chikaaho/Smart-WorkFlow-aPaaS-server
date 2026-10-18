#!/bin/bash
cd "$(dirname "$0")/.." 2>/dev/null || true
source logs/p64p2-session.env
export MAVEN_OPTS="-Xmx2g"
exec mvn -q -pl sw-bootstrap spring-boot:run -o
