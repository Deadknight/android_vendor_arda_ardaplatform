LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := arda_final_image_test_app_filter
LOCAL_MODULE_TAGS := optional

# Build-system-only phony package. It installs no APK/file itself.
# AOSP product-installed-files removes these overridden packages before install.
PACKAGES.$(LOCAL_MODULE).OVERRIDES := \
    AdasLocationTestApp \
    DisplayCompatIntentApp \
    DisplayCompatTestApp \
    GarageModeTestApp \
    EmbeddedKitchenSinkApp \
    NetworkPreferenceApp \
    RailwayReferenceApp

include $(BUILD_PHONY_PACKAGE)
