# ARDA device-wide connectivity transport core.
PRODUCT_PACKAGES += \
    ArdaPlatformPriv \
    arda_connectivityd \
    arda_connectivity_supervisor \
    arda_connectivityd_rc \
    arda_platform_privapp_permissions \
    arda_platform_default_permissions \
    arda_platform_sysconfig \
    arda_platform_permission_bootstrap \
    arda_platform_permission_bootstrap_rc

# ARDA_USB_ACCESSORY_SINK_BEGIN
# System handler for the DHU Android Open Accessory identity. It consumes only
# the attach intent; the native ARDA transport remains owner of the USB stream.
PRODUCT_PACKAGES += \
    ArdaUsbAccessorySink \
    arda_usb_accessory_sink_sysconfig
# ARDA_USB_ACCESSORY_SINK_END

# ARDA persistent DHU/AAP profile
PRODUCT_PACKAGES += \
    arda_aap_config

# ARDA_AAP_AUTOSTART_BEGIN
PRODUCT_PACKAGES += \
    arda_aap_autostart
# ARDA_AAP_AUTOSTART_END
