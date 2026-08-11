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

# ARDA_RPI4_HW_ENCODER_PERSIST_BEGIN
# The custom mapper core is installed below vendor/lib64/egl so the existing
# platform file-context rule labels it same_process_hal_file.
PRODUCT_PACKAGES += \
    libarda_minigbm_gralloc_gbm_mesa \
    arda_hw_encoder_mapper_bind \
    arda_hw_encoder_mapper_bind_rc
# ARDA_RPI4_HW_ENCODER_PERSIST_END
