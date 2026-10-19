package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionKindCapabilities;
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticCapabilities;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.DocumentDiagnosticReport;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Внешний процесс BSL Language Server, с которым плагин общается по LSP через stdin/stdout.
 * Один процесс обслуживает один проект EDT.
 */
final class BslLanguageServerProcess
    implements AutoCloseable
{
    static final class AnalysisResult
    {
        final List<Diagnostic> diagnostics;
        final List<CodeAction> quickFixes;

        AnalysisResult(List<Diagnostic> diagnostics, List<CodeAction> quickFixes)
        {
            this.diagnostics = diagnostics;
            this.quickFixes = quickFixes;
        }
    }

    private static final class DocumentState
    {
        int version;
        String text;
    }

    private static final class Client
        implements LanguageClient
    {
        private final Consumer<String> errorLog;

        Client(Consumer<String> errorLog)
        {
            this.errorLog = errorLog;
        }

        @Override
        public void telemetryEvent(Object object)
        {
            // Телеметрия не нужна
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics)
        {
            // Диагностики запрашиваются явно через textDocument/diagnostic
        }

        @Override
        public void showMessage(MessageParams messageParams)
        {
            logMessage(messageParams);
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams)
        {
            logMessage(requestParams);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message)
        {
            if (message.getType() == MessageType.Error)
                errorLog.accept(message.getMessage());
        }

        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params)
        {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unregisterCapability(UnregistrationParams params)
        {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params)
        {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final String LANGUAGE_ID = "bsl"; //$NON-NLS-1$
    private static final long POLL_MILLIS = 100;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final List<String> command;
    private final Path rootPath;
    private final Path stderrLog;
    private final Consumer<String> errorLog;

    private final Map<String, DocumentState> documents = new ConcurrentHashMap<>();

    private Process process;
    private ExecutorService executor;
    private Future<Void> listening;
    private LanguageServer server;
    private CompletableFuture<InitializeResult> initialization;

    BslLanguageServerProcess(List<String> command, Path rootPath, Path stderrLog, Consumer<String> errorLog)
    {
        this.command = List.copyOf(command);
        this.rootPath = rootPath;
        this.stderrLog = stderrLog;
        this.errorLog = errorLog;
    }

    /**
     * Запускает процесс и отправляет initialize, не дожидаясь ответа: на большой конфигурации
     * BSL LS может индексировать проект несколько минут.
     */
    synchronized void start() throws IOException
    {
        Files.createDirectories(stderrLog.getParent());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(rootPath.toFile());
        builder.redirectError(Redirect.appendTo(stderrLog.toFile()));
        process = builder.start();

        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "BSL LS: " + rootPath.getFileName()); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });

        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(new Client(errorLog),
            process.getInputStream(), process.getOutputStream(), executor, null);
        server = launcher.getRemoteProxy();
        listening = launcher.startListening();

        initialization = server.initialize(createInitializeParams()).thenApply(result -> {
            if (result.getCapabilities().getDiagnosticProvider() == null)
                throw new IllegalStateException(
                    "Сервер не поддерживает textDocument/diagnostic, нужен BSL Language Server новее"); //$NON-NLS-1$

            server.initialized(new InitializedParams());
            return result;
        });
    }

    boolean isAlive()
    {
        return process != null && process.isAlive();
    }

    boolean isReady()
    {
        return initialization != null && initialization.isDone() && !initialization.isCompletedExceptionally();
    }

    boolean isFailed()
    {
        return initialization != null && initialization.isCompletedExceptionally();
    }

    /**
     * Передает актуальный текст модуля и получает по нему диагностики и быстрые исправления.
     */
    AnalysisResult analyze(String uri, String text, Range wholeDocument, Duration timeout, BooleanSupplier canceled)
        throws IOException, TimeoutException, InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        InitializeResult initializeResult = await(initialization, deadline, canceled);
        boolean supportsCodeActions = supportsCodeActions(initializeResult);

        DocumentState state = documents.computeIfAbsent(uri, key -> new DocumentState());

        // Тексты одного модуля нельзя перемешивать: смещения диагностик считаются по отправленному тексту
        synchronized (state)
        {
            synchronizeDocument(uri, text, state);

            DocumentDiagnosticReport report = await(
                server.getTextDocumentService().diagnostic(new DocumentDiagnosticParams(new TextDocumentIdentifier(uri))),
                deadline, canceled);

            List<Diagnostic> diagnostics = report != null && report.isLeft() && report.getLeft().getItems() != null
                ? report.getLeft().getItems() : Collections.emptyList();

            List<CodeAction> quickFixes = Collections.emptyList();
            if (supportsCodeActions && !diagnostics.isEmpty())
                quickFixes = requestQuickFixes(uri, wholeDocument, diagnostics, deadline, canceled);

            return new AnalysisResult(diagnostics, quickFixes);
        }
    }

    @Override
    public synchronized void close()
    {
        if (process == null)
            return;

        try
        {
            if (process.isAlive() && isReady())
            {
                server.shutdown().get(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                server.exit();
            }
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                destroyForcibly();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            destroyForcibly();
        }
        catch (ExecutionException | TimeoutException | RuntimeException e)
        {
            destroyForcibly();
        }
        finally
        {
            if (listening != null)
                listening.cancel(true);
            if (executor != null)
                executor.shutdownNow();
            documents.clear();
            process = null;
        }
    }

    /**
     * bsl-language-server.exe запускает JVM дочерним процессом, поэтому завершаем все дерево.
     */
    private void destroyForcibly()
    {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    @SuppressWarnings("deprecation")
    private InitializeParams createInitializeParams()
    {
        String rootUri = rootPath.toUri().toString();

        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        textDocument.setSynchronization(new SynchronizationCapabilities(false, false, false));
        textDocument.setDiagnostic(new DiagnosticCapabilities(false, false));
        textDocument.setCodeAction(new CodeActionCapabilities(
            new CodeActionLiteralSupportCapabilities(new CodeActionKindCapabilities(List.of(CodeActionKind.QuickFix))),
            false));

        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(textDocument);

        InitializeParams params = new InitializeParams();
        params.setProcessId((int)ProcessHandle.current().pid());
        params.setClientInfo(new ClientInfo("1C:EDT")); //$NON-NLS-1$
        params.setRootUri(rootUri);
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(rootUri, String.valueOf(rootPath.getFileName()))));
        params.setCapabilities(capabilities);
        return params;
    }

    private static boolean supportsCodeActions(InitializeResult initializeResult)
    {
        Either<Boolean, ?> provider = initializeResult.getCapabilities().getCodeActionProvider();
        if (provider == null)
            return false;

        return provider.isRight() || Boolean.TRUE.equals(provider.getLeft());
    }

    private void synchronizeDocument(String uri, String text, DocumentState state)
    {
        if (state.text == null)
        {
            state.version = 1;
            server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, LANGUAGE_ID, state.version, text)));
        }
        else if (!state.text.equals(text))
        {
            state.version++;
            server.getTextDocumentService()
                .didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, state.version),
                    List.of(new TextDocumentContentChangeEvent(text))));
        }
        state.text = text;
    }

    private List<CodeAction> requestQuickFixes(String uri, Range wholeDocument, List<Diagnostic> diagnostics,
        long deadline, BooleanSupplier canceled) throws IOException, TimeoutException, InterruptedException
    {
        CodeActionContext context = new CodeActionContext(diagnostics, List.of(CodeActionKind.QuickFix));
        List<Either<Command, CodeAction>> actions = await(server.getTextDocumentService()
            .codeAction(new CodeActionParams(new TextDocumentIdentifier(uri), wholeDocument, context)), deadline,
            canceled);

        if (actions == null)
            return Collections.emptyList();

        List<CodeAction> quickFixes = new ArrayList<>();
        for (Either<Command, CodeAction> action : actions)
        {
            if (action != null && action.isRight() && action.getRight().getEdit() != null)
                quickFixes.add(action.getRight());
        }
        return quickFixes;
    }

    private <T> T await(CompletableFuture<T> future, long deadline, BooleanSupplier canceled)
        throws IOException, TimeoutException, InterruptedException
    {
        // Инициализацию не прерываем ни по отмене, ни по таймауту: она нужна следующим проверкам
        boolean cancelable = future != initialization;
        while (true)
        {
            if (canceled.getAsBoolean())
            {
                if (cancelable)
                    future.cancel(true);
                throw new CancellationException();
            }

            if (!isAlive() && !future.isDone())
                throw new IOException("Процесс BSL Language Server завершился, подробности в " + stderrLog); //$NON-NLS-1$

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
            {
                if (cancelable)
                    future.cancel(true);
                throw new TimeoutException();
            }

            try
            {
                return future.get(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, POLL_MILLIS),
                    TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e)
            {
                // Продолжаем ждать с проверкой отмены
            }
            catch (ExecutionException e)
            {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IOException(cause.getMessage(), cause);
            }
        }
    }
}
