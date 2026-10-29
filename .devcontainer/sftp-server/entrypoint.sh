#!/bin/bash
# Creates the user from "name::uid::dir,dir" (a password between the first colons is not supported: keys only),
# makes any missing host keys, runs /etc/sftp.d/*.sh, then sshd in the foreground.
set -euo pipefail

IFS=: read -r user _ uid _ dirs <<< "${1:?usage: name::uid::dir,dir}"
home=/home/$user

if ! id "$user" >/dev/null 2>&1; then
  adduser -D -H -h "$home" -s /sbin/nologin -u "$uid" "$user"
  echo "$user:*" | chpasswd -e   # no password, but not locked: a locked account can't log in with a key either
fi
install -d -o root -g root -m 755 "$home"   # sshd's ChrootDirectory must be root's
install -d -o "$user" -g "$user" -m 700 "$home/.ssh"
IFS=, read -ra folders <<< "$dirs"
for d in "${folders[@]}"; do
  install -d -o "$user" -g "$user" -m 755 "$home/$d"
done

ssh-keygen -A >/dev/null   # whatever host keys are missing; a setup script may replace them
for s in /etc/sftp.d/*.sh; do
  [ -f "$s" ] && bash "$s"
done

exec /usr/sbin/sshd -D -e
