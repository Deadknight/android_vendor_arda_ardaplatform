# Arda AAOS Platform Integration

AAOS product integration for the Arda connectivity transport core.

Installed components:

- `/product/priv-app/ArdaPlatformPriv/ArdaPlatformPriv.apk`
- `/system_ext/bin/arda-connectivityd`
- `/product/bin/arda-connectivity-supervisor`
- `/product/etc/init/arda-connectivityd.rc`
- Platform runtime permission and sysconfig files

Runtime architecture:

- ArdaPlatform owns AAOS LocalOnlyHotspot and RFCOMM transport.
- arda-connectivityd owns the Bluetooth-to-Wi-Fi handoff protocol.
- Sensor, GNSS, location and VHAL ownership remain outside this package.
- The connectivity token is generated on-device and is not stored in the image.
