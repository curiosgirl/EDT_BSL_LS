package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.xtext.ui.editor.quickfix.Fix;
import org.eclipse.xtext.ui.editor.quickfix.IssueResolutionAcceptor;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.bsl.ui.quickfix.AbstractExternalQuickfixProvider;

public class BslQuickFix
    extends AbstractExternalQuickfixProvider
{

    @Fix("bsl-language-server")
    public void processBslLanguageServerDiagnostic(final Issue issue, final IssueResolutionAcceptor acceptor)
    {
        String[] issueData = issue.getData();
        if (issueData == null)
            return;

        for (String issueLine : issueData)
        {
            QuickFixData quickFix = QuickFixData.decode(issueLine);
            if (quickFix == null)
                continue;

            acceptor.accept(issue, quickFix.title, quickFix.description, (String)null,
                new AbstractExternalQuickfixProvider.ExternalQuickfixModification<>(issue, EObject.class,
                    module -> createEdit(quickFix)));
        }
    }

    private static TextEdit createEdit(QuickFixData quickFix)
    {
        if (quickFix.edits.size() == 1)
            return toReplaceEdit(quickFix.edits.get(0));

        MultiTextEdit edit = new MultiTextEdit();
        for (QuickFixData.Edit child : quickFix.edits)
            edit.addChild(toReplaceEdit(child));
        return edit;
    }

    private static ReplaceEdit toReplaceEdit(QuickFixData.Edit edit)
    {
        return new ReplaceEdit(edit.offset(), edit.length(), edit.newText() == null ? "" : edit.newText()); //$NON-NLS-1$
    }
}
