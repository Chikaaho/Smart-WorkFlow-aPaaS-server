#!/bin/bash
cd "$(dirname "$0")/.." 2>/dev/null || true
source logs/p64p2-session.env
exec java @logs/p64p2-java-args.txt
