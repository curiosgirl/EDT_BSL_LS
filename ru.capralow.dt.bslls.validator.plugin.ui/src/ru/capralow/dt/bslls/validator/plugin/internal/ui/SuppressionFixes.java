package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;

/**
 * Быстрые исправления, отключающие диагностику комментарием BSL LS. Сервер предлагает такие же правки,
 * но как действия refactor без привязки к диагностике, поэтому плагин формирует их сам.
 */
final class SuppressionFixes
{
    private static final String COMMENT_PREFIX = "// BSLLS:"; //$NON-NLS-1$
    private static final String COMMENT_SUFFIX = "-off"; //$NON-NLS-1$

    static List<QuickFixData> create(String diagnosticCode, int line, String description, IDocument doc)
    {
        List<QuickFixData> fixes = new ArrayList<>();
        if (diagnosticCode.isEmpty())
            return fixes;

        String comment = COMMENT_PREFIX + diagnosticCode + COMMENT_SUFFIX;

        QuickFixData.Edit lineEdit = lineEdit(comment, line, doc);
        if (lineEdit != null)
            fixes.add(new QuickFixData(MessageFormat.format("{0}Отключить {1} в строке", //$NON-NLS-1$
                BslValidator.BSL_LS_PREFIX, diagnosticCode), description, List.of(lineEdit)));

        fixes.add(new QuickFixData(MessageFormat.format("{0}Отключить {1} во всем модуле", //$NON-NLS-1$
            BslValidator.BSL_LS_PREFIX, diagnosticCode), description,
            List.of(new QuickFixData.Edit(0, 0, comment + lineDelimiter(doc)))));

        return fixes;
    }

    /**
     * Комментарий в конце строки нельзя ставить внутри многострочного строкового литерала:
     * он станет частью текста.
     */
    private static QuickFixData.Edit lineEdit(String comment, int line, IDocument doc)
    {
        try
        {
            IRegion region = doc.getLineInformation(line);
            String lineText = doc.get(region.getOffset(), region.getLength());

            if (lineText.stripLeading().startsWith("|") || countQuotes(lineText) % 2 != 0) //$NON-NLS-1$
                return null;

            return new QuickFixData.Edit(region.getOffset() + region.getLength(), 0, " " + comment); //$NON-NLS-1$
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    private static int countQuotes(String text)
    {
        int count = 0;
        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == '"')
                count++;
        }
        return count;
    }

    private static String lineDelimiter(IDocument doc)
    {
        try
        {
            String delimiter = doc.getLineDelimiter(0);
            return delimiter == null ? System.lineSeparator() : delimiter;
        }
        catch (BadLocationException e)
        {
            return System.lineSeparator();
        }
    }

    private SuppressionFixes()
    {
    }
}
