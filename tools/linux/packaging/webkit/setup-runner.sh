#!/usr/bin/env bash
# Host orchestration only. Compilers/libraries still come from the verified OCI
# seed and signed snapshot. Never run apt or change services on a developer host.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true ]] || { echo 'Runner setup is for GitHub Actions only' >&2; exit 1; }
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'A Linux x86_64 runner is required' >&2; exit 1; }

case ${RUNNER_ENVIRONMENT:-} in
  github-hosted)
    command -v sudo apt-get >/dev/null || {
      echo 'Select a GitHub-hosted Ubuntu runner, or pre-provision a self-hosted Linux runner' >&2; exit 1;
    }
    # Explicit installation: runner image contents are not an availability contract.
    # Use its signed distribution repositories; no third-party curl installers.
    sudo -n apt-get -o DPkg::Lock::Timeout=60 -o Acquire::Retries=3 update
    sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 \
      -o Acquire::Retries=3 install -y --no-install-recommends \
      podman uidmap slirp4netns fuse-overlayfs python3 curl jq gh ca-certificates zstd
    ;;
  self-hosted)
    echo 'Self-hosted runner: verifying pre-provisioned tools; no host installation or configuration changes'
    ;;
  *) echo 'Unknown RUNNER_ENVIRONMENT; refusing automatic host setup' >&2; exit 1 ;;
esac

for tool in podman python3 curl jq gh tar zstd newuidmap newgidmap slirp4netns fuse-overlayfs timeout; do
  command -v "$tool" >/dev/null || {
    echo "Missing runner tool: $tool. Provision it before retrying; rootless Podman must support cgroup v2." >&2
    exit 1
  }
done
python3 -c 'import sys; assert sys.version_info >= (3, 10), "Python 3.10+ is required"'
timeout 30s podman info --format json | python3 -c '
import json, sys
host = json.load(sys.stdin)["host"]
if not host["security"]["rootless"] or host["cgroupVersion"] != "v2":
    sys.exit("Rootless Podman with cgroup v2 is required; do not disable memory/PID limits or use sudo podman")
'
podman --version
python3 --version
gh --version
jq --version
