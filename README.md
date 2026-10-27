# HearLink

Sends the sound from the phone's microphone straight to Bluetooth (bone conduction) headphones,
with volume, left/right balance, tone controls and clear-sound options. Keeps running with the
screen off. Nothing is recorded or stored.

Extras:
- Read text: take a photo (or pick one) and the text is shown big and read aloud in the headphones.
  Text recognition runs on the phone (Google ML Kit, bundled), nothing is uploaded.
- Two saved personal settings (e.g. talking / TV).
- Anti-squeal (frequency shift + automatic notch filters + volume guard), room-echo/hum reduction, soft-voice lift, and a fixed limiter that protects the ears.

Build: push to GitHub; the workflow builds `HearLink.apk` and publishes it as the `latest` release.
The signing key in `signing/` is shared on purpose so updates install over the previous version.
