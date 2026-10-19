package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

public class PreferenceInitializer
    extends AbstractPreferenceInitializer
{
    @Override
    public void initializeDefaultPreferences()
    {
        IPreferenceStore store = BslValidatorPlugin.getDefault().getPreferenceStore();
        store.setDefault(PreferenceConstants.EXECUTABLE_PATH, ""); //$NON-NLS-1$
        store.setDefault(PreferenceConstants.JAVA_PATH, ""); //$NON-NLS-1$
        store.setDefault(PreferenceConstants.JVM_ARGUMENTS, "-Xmx4g"); //$NON-NLS-1$
        store.setDefault(PreferenceConstants.MODULE_TIMEOUT_SECONDS,
            PreferenceConstants.DEFAULT_MODULE_TIMEOUT_SECONDS);
    }
}
