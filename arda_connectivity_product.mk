# ARDA device-wide connectivity transport core.
PRODUCT_PACKAGES += \
    ArdaPlatformPriv \
    arda_connectivityd \
    arda_connectivity_supervisor \
    arda_connectivityd_rc \
    arda_platform_privapp_permissions \
    arda_platform_default_permissions \
    arda_platform_sysconfig \
    arda_rn3b_permission_bootstrap \
    arda_rn3b_permission_bootstrap_rc \
    arda_aap_config \
    ArdaProjectionInsets

# ARDA_AAP_AUTOSTART_BEGIN
PRODUCT_PACKAGES += \
    arda_aap_autostart \
    arda_aap_autostart_rc
# ARDA_AAP_AUTOSTART_END

# The encoder Surface allocator is integrated in the official minigbm APEX.
# The legacy vendor mapper late-bind override is intentionally not packaged.

# ARDA: install ArdaPlatform for AAOS system + full automotive users.
# This makes the built-in priv-app available to foreground Driver users
# created after a fresh image/data boot.
PRODUCT_COPY_FILES += \
    vendor/arda/ardaplatform/arda-platform-user-types.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/arda-platform-user-types.xml

# ARDA_USB_ACCESSORY_SINK_BEGIN
# Existing AOAP accessory intent sink. It prevents the AAOS
# "No installed apps work with this USB accessory" dialog while
# arda-connectivityd continues to own /dev/usb_accessory data I/O.
PRODUCT_PACKAGES += \
    ArdaUsbAccessorySink

PRODUCT_PACKAGES += \
    arda_usb_accessory_sink_sysconfig
# ARDA_USB_ACCESSORY_SINK_END
