#!/data/data/com.termux/files/usr/bin/bash
# setup-and-run.sh - one-shot Termux bootstrap for the Mabu app prototype.
# Run once from inside Termux:
#     bash /sdcard/mabu-app/setup-and-run.sh
#
# Idempotent: re-running is safe.

set -e
LOG=/sdcard/mabu-app/setup.log
exec > >(tee -a "$LOG") 2>&1
echo "=== $(date) setup-and-run starting ==="

# 1. Allow /sdcard access (user must tap "Allow" the first time)
termux-setup-storage || true
sleep 1

# 1b. Pin a fast mirror (official CDN) before pkg update.
# The default picker sometimes lands on a slow Chinese mirror from US IPs.
echo 'deb https://packages.termux.dev/apt/termux-main stable main' \
    > $PREFIX/etc/apt/sources.list

# 2. Update + install runtime
apt update
# Just python + termux-api for now. numpy and opencv come later -- we want to
# validate motors first, before sinking time into vision deps.
apt install -y python termux-api

# 3. Allow external-apps RUN_COMMAND intents so the host can drive us later
mkdir -p ~/.termux
if ! grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null; then
    echo 'allow-external-apps=true' >> ~/.termux/termux.properties
fi
# Reload settings (Termux reads on next intent)
termux-reload-settings 2>/dev/null || true

# 4. Python deps via pip. Only pyserial for now -- vision deps deferred.
pip install --no-input pyserial

# 5. Copy our app files into ~ so RUN_COMMAND has a clean cwd
mkdir -p ~/mabu-app
cp -f /sdcard/mabu-app/*.py /sdcard/mabu-app/*.xml ~/mabu-app/

# 6. Sanity checks
echo
echo "=== Sanity ==="
python -V
python -c "import serial; print('pyserial', serial.__version__)"
ls -l ~/mabu-app/

# 7. First motor test
echo
echo "=== Running mabu.py selftest ==="
cd ~/mabu-app
python mabu.py

echo
echo "=== Setup complete. The host can now invoke scripts via RUN_COMMAND. ==="
