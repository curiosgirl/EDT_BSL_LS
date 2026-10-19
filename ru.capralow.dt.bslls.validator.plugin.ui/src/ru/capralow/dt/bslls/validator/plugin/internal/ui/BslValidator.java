package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.util.CancelIndicator;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.BslResource;
import com._1c.g5.v8.dt.bsl.validation.CustomValidationMessageAcceptor;
import com._1c.g5.v8.dt.bsl.validation.IExternalBslValidator;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.google.inject.Inject;

public class BslValidator
    implements IExternalBslValidator
{
    static final String BSL_LS_PREFIX = "[BSL LS] "; //$NON-NLS-1$

    private static final String QUICKFIX_CODE = "bsl-language-server"; //$NON-NLS-1$

    private static final long LONG_VALIDATION_MILLIS = 10000;

    @Inject
    private IV8ProjectManager projectManager;

    @Override
    public boolean needValidation(EObject object)
    {
        if (!(object instanceof Module))
            return false;

        return ((BslResource)((Module)object).eResource()).isDeepAnalysing();
    }

    @Override
    public void validate(EObject object, CustomValidationMessageAcceptor messageAcceptor, CancelIndicator monitor)
    {
        if (monitor.isCanceled())
            return;

        IV8Project v8Project = projectManager.getProject(object);
        if (v8Project == null)
            return;

        IProject project = v8Project.getProject();
        BslLanguageServerManager serverManager = BslValidatorPlugin.getDefault().getServerManager();
        BslLanguageServerProcess server = serverManager.getServer(project);
        if (server == null)
            return;

        Module module = (Module)object;
        IFile moduleFile =
            ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(EcoreUtil.getURI(module).toPlatformString(true)));
        IPath moduleLocation = moduleFile.getLocation();
        if (moduleLocation == null)
            return;

        ICompositeNode node = NodeModelUtils.findActualNodeFor(module);
        if (node == null)
            return;

        String moduleText = node.getText();
        Document doc = new Document(moduleText);
        String moduleUri = moduleLocation.toFile().toPath().toUri().toString();

        long startTime = System.currentTimeMillis();
        BslLanguageServerProcess.AnalysisResult result;
        try
        {
            result = server.analyze(moduleUri, moduleText, wholeDocumentRange(doc), moduleTimeout(),
                monitor::isCanceled);
        }
        catch (CancellationException e)
        {
            return;
        }
        catch (TimeoutException e)
        {
            String problem = server.isReady()
                ? MessageFormat.format("Проверка модуля не уложилась в таймаут: {0}", moduleLocation) //$NON-NLS-1$
                : "Сервер еще индексирует проект, модули пропускаются до окончания запуска"; //$NON-NLS-1$
            serverManager.reportProblem(project, problem);
            return;
        }
        catch (IOException e)
        {
            serverManager.reportProblem(project, e.getMessage());
            return;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return;
        }
        serverManager.clearProblem(project);

        Map<String, List<CodeAction>> quickFixes = groupQuickFixes(result.quickFixes);
        for (Diagnostic diagnostic : result.diagnostics)
        {
            if (monitor.isCanceled())
                return;

            registerIssue(object, messageAcceptor, diagnostic, quickFixes.get(diagnosticKey(diagnostic)), moduleUri,
                doc);
        }

        long difference = System.currentTimeMillis() - startTime;
        if (difference > LONG_VALIDATION_MILLIS)
            BslValidatorPlugin.log(BslValidatorPlugin.createInfoStatus(MessageFormat.format(
                "{0}Долгая проверка модуля ({1} сек): {2}", BSL_LS_PREFIX, difference / 1000, moduleLocation))); //$NON-NLS-1$
    }

    private static Duration moduleTimeout()
    {
        int seconds = BslValidatorPlugin.getDefault().getPreferenceStore()
            .getInt(PreferenceConstants.MODULE_TIMEOUT_SECONDS);
        return Duration.ofSeconds(seconds > 0 ? seconds : PreferenceConstants.DEFAULT_MODULE_TIMEOUT_SECONDS);
    }

    private static Range wholeDocumentRange(Document doc)
    {
        int lastLine = Math.max(doc.getNumberOfLines() - 1, 0);
        int lastLineLength = 0;
        try
        {
            lastLineLength = doc.getLineLength(lastLine);
        }
        catch (BadLocationException e)
        {
            // Пустой документ
        }
        return new Range(new Position(0, 0), new Position(lastLine, lastLineLength));
    }

    private static Map<String, List<CodeAction>> groupQuickFixes(List<CodeAction> quickFixes)
    {
        Map<String, List<CodeAction>> result = new HashMap<>();
        for (CodeAction quickFix : quickFixes)
        {
            if (quickFix.getDiagnostics() == null)
                continue;

            for (Diagnostic diagnostic : quickFix.getDiagnostics())
                result.computeIfAbsent(diagnosticKey(diagnostic), key -> new ArrayList<>()).add(quickFix);
        }
        return result;
    }

    /**
     * Сервер возвращает в быстрых исправлениях копии диагностик, поля которых могут отличаться
     * (например, пустой список tags), поэтому сопоставляем по коду и диапазону.
     */
    private static String diagnosticKey(Diagnostic diagnostic)
    {
        return diagnosticCode(diagnostic) + '@' + diagnostic.getRange();
    }

    private static void registerIssue(EObject object, CustomValidationMessageAcceptor messageAcceptor,
        Diagnostic diagnostic, List<CodeAction> quickFixes, String moduleUri, Document doc)
    {
        int[] offsetAndLength = getOffsetAndLength(diagnostic.getRange(), doc);
        if (offsetAndLength == null)
            return;

        String code = diagnosticCode(diagnostic);
        String message = code.isEmpty() ? BSL_LS_PREFIX + diagnostic.getMessage()
            : MessageFormat.format("{0}{1} ({2})", BSL_LS_PREFIX, diagnostic.getMessage(), code); //$NON-NLS-1$

        String[] issueData = getIssueData(diagnostic, quickFixes, moduleUri, doc);

        DiagnosticSeverity severity = diagnostic.getSeverity();
        if (severity == DiagnosticSeverity.Error)
            messageAcceptor.acceptError(message, object, offsetAndLength[0], offsetAndLength[1], QUICKFIX_CODE,
                issueData);

        else if (severity == null || severity == DiagnosticSeverity.Warning)
            messageAcceptor.acceptWarning(message, object, offsetAndLength[0], offsetAndLength[1], QUICKFIX_CODE,
                issueData);

        else
            messageAcceptor.acceptInfo(message, object, offsetAndLength[0], offsetAndLength[1], QUICKFIX_CODE,
                issueData);
    }

    private static String diagnosticCode(Diagnostic diagnostic)
    {
        Either<String, Integer> code = diagnostic.getCode();
        if (code == null)
            return ""; //$NON-NLS-1$

        return code.isLeft() ? code.getLeft() : String.valueOf(code.getRight());
    }

    private static String[] getIssueData(Diagnostic diagnostic, List<CodeAction> quickFixes, String moduleUri,
        Document doc)
    {
        List<String> issueData = new ArrayList<>();
        for (CodeAction quickFix : quickFixes == null ? List.<CodeAction>of() : quickFixes)
        {
            List<TextEdit> edits = getModuleEdits(quickFix.getEdit(), moduleUri);
            if (edits.isEmpty())
                continue;

            List<QuickFixData.Edit> fixEdits = new ArrayList<>();
            for (TextEdit edit : edits)
            {
                int[] offsetAndLength = getOffsetAndLength(edit.getRange(), doc);
                if (offsetAndLength == null)
                {
                    fixEdits = null;
                    break;
                }
                fixEdits.add(new QuickFixData.Edit(offsetAndLength[0], offsetAndLength[1], edit.getNewText()));
            }

            if (fixEdits == null || !QuickFixData.hasNoOverlaps(fixEdits))
                continue;

            fixEdits.sort(Comparator.comparingInt(QuickFixData.Edit::offset));
            issueData.add(QuickFixData.encode(
                new QuickFixData(BSL_LS_PREFIX + quickFix.getTitle(), diagnostic.getMessage(), fixEdits)));
        }

        for (QuickFixData suppression : SuppressionFixes.create(diagnosticCode(diagnostic),
            diagnostic.getRange().getStart().getLine(), diagnostic.getMessage(), doc))
            issueData.add(QuickFixData.encode(suppression));

        return issueData.toArray(new String[0]);
    }

    /**
     * Быстрые исправления, затрагивающие другие модули, не применяются: EDT правит только текущий документ.
     */
    private static List<TextEdit> getModuleEdits(WorkspaceEdit workspaceEdit, String moduleUri)
    {
        if (workspaceEdit == null)
            return List.of();

        Map<String, List<TextEdit>> editsByUri = new HashMap<>();
        if (workspaceEdit.getChanges() != null)
            editsByUri.putAll(workspaceEdit.getChanges());

        if (workspaceEdit.getDocumentChanges() != null)
        {
            for (var documentChange : workspaceEdit.getDocumentChanges())
            {
                if (!documentChange.isLeft())
                    return List.of();

                TextDocumentEdit textDocumentEdit = documentChange.getLeft();
                editsByUri.computeIfAbsent(textDocumentEdit.getTextDocument().getUri(), key -> new ArrayList<>())
                    .addAll(textDocumentEdit.getEdits());
            }
        }

        if (editsByUri.size() != 1)
            return List.of();

        Map.Entry<String, List<TextEdit>> entry = editsByUri.entrySet().iterator().next();
        if (!sameUri(entry.getKey(), moduleUri))
            return List.of();

        Collection<TextEdit> edits = entry.getValue();
        return edits == null ? List.of() : List.copyOf(edits);
    }

    private static boolean sameUri(String first, String second)
    {
        try
        {
            return Paths.get(URI.create(first)).equals(Paths.get(URI.create(second)));
        }
        catch (IllegalArgumentException | FileSystemNotFoundException e)
        {
            return first.equalsIgnoreCase(second);
        }
    }

    private static int[] getOffsetAndLength(Range range, Document doc)
    {
        try
        {
            int offset = doc.getLineOffset(range.getStart().getLine()) + range.getStart().getCharacter();
            int endOffset = doc.getLineOffset(range.getEnd().getLine()) + range.getEnd().getCharacter();
            return new int[] { offset, endOffset - offset };
        }
        catch (BadLocationException e)
        {
            BslValidatorPlugin.log(
                BslValidatorPlugin.createErrorStatus(Messages.BslValidator_Bad_Location_Exception, e));
            return null;
        }
    }
}
