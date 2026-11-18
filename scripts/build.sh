#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
cd "$project_root"

if ! command -v mvn >/dev/null 2>&1; then
  printf '%s\n' 'Maven não encontrado. Instale Maven 3.9+ e adicione mvn ao PATH.' >&2
  exit 1
fi

mvn -B clean verify
