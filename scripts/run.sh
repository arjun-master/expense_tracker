#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PORT="${1:-8080}"
mkdir -p build/classes

find src/main/java -name '*.java' | sort > build/main-sources.txt
javac --release 21 -d build/classes @build/main-sources.txt

java -Dapp.port="$PORT" -cp build/classes com.acme.expenses.ExpenseTrackerApplication "$PORT"
