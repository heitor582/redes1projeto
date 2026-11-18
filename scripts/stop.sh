#!/usr/bin/env sh
set -eu

case "${OSTYPE:-}" in
  msys*|cygwin*)
    if ! command -v powershell.exe >/dev/null 2>&1; then
      printf '%s\n' 'PowerShell não encontrado. Abra este script pelo Git Bash no Windows.' >&2
      exit 1
    fi
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command '
      $ErrorActionPreference = "Stop"
      $pattern = "netpulse-(server|client)-[0-9][0-9A-Za-z.+_-]*\.jar"
      $targets = Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @("java.exe", "javaw.exe") -and $_.CommandLine -match $pattern
      }
      if (-not $targets) {
        Write-Output "Nenhum servidor ou cliente NetPulse iniciado pelos JARs do projeto está em execução."
        exit 0
      }
      $jcmdPath = (Get-Command jcmd.exe -ErrorAction SilentlyContinue).Source
      if (-not $jcmdPath) {
        $javaPath = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
        if ($javaPath) {
          $candidate = Join-Path (Split-Path $javaPath) "jcmd.exe"
          if (Test-Path $candidate) { $jcmdPath = $candidate }
        }
      }
      foreach ($target in $targets) {
        $stopped = $false
        if ($jcmdPath) {
          & $jcmdPath $target.ProcessId VM.exit
          $stopped = ($LASTEXITCODE -eq 0)
        }
        if (-not $stopped) {
          $stillRunning = Get-Process -Id $target.ProcessId -ErrorAction SilentlyContinue
          if ($stillRunning) {
            Write-Warning ("Encerramento normal indisponível; encerrando o PID " + $target.ProcessId + " à força.")
            Stop-Process -Id $target.ProcessId -Force
          }
        }
        Write-Output ("Processo encerrado: " + $target.ProcessId)
      }
    '
    exit $?
    ;;
esac

pattern='netpulse-(server|client)-[0-9][0-9A-Za-z.+_-]*[.]jar'
pids=$(pgrep -f "$pattern" || true)

if [ -z "$pids" ]; then
  printf '%s\n' 'Nenhum servidor ou cliente NetPulse iniciado pelos JARs do projeto está em execução.'
  exit 0
fi

for pid in $pids; do
  if kill -TERM "$pid" 2>/dev/null; then
    printf 'Encerramento solicitado para o processo %s.\n' "$pid"
  fi
done

printf '%s\n' 'O sinal de encerramento permite que o servidor execute o fechamento e salve o histórico pendente.'
