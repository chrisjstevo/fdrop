#!/bin/bash
# Run by the dev SFTP destinations (sftp-target, sftp-target2) from /etc/sftp.d, just before sshd starts:
# - uses the fixed host key scripts/dev-certs.sh made for this server ($HOST_KEY), so a host key pinned in fdrop's
#   admin screens still matches after the container is recreated;
# - installs every key in the mounted keys folder as an authorized key for user "target", then keeps doing so every
#   2 seconds, so a key pasted into .devcontainer/sftp-target*/authorized_keys works at once.
keys=/home/target/.ssh/keys
dest=/home/target/.ssh/authorized_keys

if [ -n "${HOST_KEY:-}" ] && [ -f "$HOST_KEY" ]; then
  install -m 600 "$HOST_KEY" /etc/ssh/ssh_host_ed25519_key
  ssh-keygen -y -f /etc/ssh/ssh_host_ed25519_key > /etc/ssh/ssh_host_ed25519_key.pub
fi

sync_keys() {
  local tmp
  tmp=$(mktemp)
  cat "$keys"/* 2>/dev/null | grep -v -e '^#' -e '^[[:space:]]*$' > "$tmp"
  if ! cmp -s "$tmp" "$dest"; then
    # owned by the user and not group or world writable, as sshd's StrictModes requires
    install -o target -m 600 "$tmp" "$dest"
  fi
  rm -f "$tmp"
}

sync_keys
(while sleep 2; do sync_keys; done) &
