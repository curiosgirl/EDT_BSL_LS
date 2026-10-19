package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import org.eclipse.osgi.util.NLS;

final class Messages
    extends NLS
{
    private static final String BUNDLE_NAME = "ru.capralow.dt.bslls.validator.plugin.internal.ui.messages"; //$NON-NLS-1$

    public static String BslValidator_Bad_Location_Exception;
    public static String BslValidator_Failed_to_create_injector_for_0;

    public static String PreferencePage_Description;
    public static String PreferencePage_Executable;
    public static String PreferencePage_Java;
    public static String PreferencePage_JvmArguments;
    public static String PreferencePage_ModuleTimeout;

    static
    {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages()
    {
    }
}
