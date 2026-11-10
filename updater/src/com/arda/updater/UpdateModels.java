package com.arda.updater;

import java.util.ArrayList;
import java.util.List;

final class UpdateModels {
    static final class Component {
        String id;
        String packageName;
        long versionCode;
        long size;
        String sha256;
        String signerSha256;
        String url;
        boolean rebootRequired;
        boolean staged;
    }

    static final class Plan {
        String release;
        long serial;
        String device;
        final List<Component> components = new ArrayList<>();
        final List<String> blocked = new ArrayList<>();

        boolean requiresStaged() {
            for (Component c : components) if (c.staged) return true;
            return false;
        }

        boolean rebootRequired() {
            for (Component c : components) {
                if (c.rebootRequired) return true;
            }
            return false;
        }
    }

    static final class Downloaded {
        final Component component;
        final java.io.File file;

        Downloaded(Component component, java.io.File file) {
            this.component = component;
            this.file = file;
        }
    }

    private UpdateModels() {}
}
