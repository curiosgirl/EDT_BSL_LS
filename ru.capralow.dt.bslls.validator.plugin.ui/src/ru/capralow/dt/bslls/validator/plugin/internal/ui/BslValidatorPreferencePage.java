package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class BslValidatorPreferencePage
    extends FieldEditorPreferencePage
    implements IWorkbenchPreferencePage
{
    public BslValidatorPreferencePage()
    {
        super(GRID);
        setPreferenceStore(BslValidatorPlugin.getDefault().getPreferenceStore());
        setDescription(Messages.PreferencePage_Description);
    }

    @Override
    public void init(IWorkbench workbench)
    {
        // Настройки берутся из хранилища плагина
    }

    @Override
    protected void createFieldEditors()
    {
        FileFieldEditor executable = new FileFieldEditor(PreferenceConstants.EXECUTABLE_PATH,
            Messages.PreferencePage_Executable, true, getFieldEditorParent());
        executable.setFileExtensions(new String[] { "*.exe;*.jar", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
        executable.setEmptyStringAllowed(true);
        addField(executable);

        FileFieldEditor java = new FileFieldEditor(PreferenceConstants.JAVA_PATH, Messages.PreferencePage_Java, true,
            getFieldEditorParent());
        java.setEmptyStringAllowed(true);
        addField(java);

        addField(new StringFieldEditor(PreferenceConstants.JVM_ARGUMENTS, Messages.PreferencePage_JvmArguments,
            getFieldEditorParent()));

        IntegerFieldEditor timeout = new IntegerFieldEditor(PreferenceConstants.MODULE_TIMEOUT_SECONDS,
            Messages.PreferencePage_ModuleTimeout, getFieldEditorParent());
        timeout.setValidRange(1, 3600);
        addField(timeout);
    }
}
