package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Держит по одному процессу BSL LS на проект EDT и перезапускает его при изменении настроек
 * плагина или пользовательского файла конфигурации.
 */
final class BslLanguageServerManager
{
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private static final class ServerEntry
    {
        final ServerKey key;
        final BslLanguageServerProcess process;
        final long startedAt;
        String lastLoggedProblem;

        ServerEntry(ServerKey key, BslLanguageServerProcess process)
        {
            this.key = key;
            this.process = process;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private record ServerKey(List<String> launchCommand, Path userConfiguration, long userConfigurationModified,
        Path effectiveConfiguration)
    {
    }

    private final Map<IProject, ServerEntry> servers = new HashMap<>();
    private final Map<IProject, String> lastStartProblems = new HashMap<>();

    /**
     * Возвращает процесс BSL LS проекта или {@code null}, если запустить его нельзя.
     */
    synchronized BslLanguageServerProcess getServer(IProject project)
    {
        ServerKey key;
        try
        {
            key = createKey(project);
        }
        catch (IOException e)
        {
            logStartProblemOnce(project, e.getMessage());
            return null;
        }

        if (key == null)
            return null;

        ServerEntry entry = servers.get(project);
        if (entry != null && entry.key.equals(key))
        {
            // Ошибка инициализации повторится при тех же настройках, перезапускаем только упавший процесс
            if (entry.process.isFailed())
                return null;

            if (entry.process.isAlive())
                return entry.process;

            if (System.currentTimeMillis() - entry.startedAt < RETRY_AFTER_FAILURE.toMillis())
                return null;
        }

        if (entry != null)
            entry.process.close();

        return startServer(project, key);
    }

    /**
     * Сообщает о проблеме с сервером один раз, пока она повторяется, чтобы не засорять журнал.
     */
    synchronized void reportProblem(IProject project, String problem)
    {
        ServerEntry entry = servers.get(project);
        if (entry == null || Objects.equals(entry.lastLoggedProblem, problem))
            return;

        entry.lastLoggedProblem = problem;
        BslValidatorPlugin.log(BslValidatorPlugin.createErrorStatus(BslValidator.BSL_LS_PREFIX + project.getName()
            + ": " + problem)); //$NON-NLS-1$
    }

    synchronized void clearProblem(IProject project)
    {
        ServerEntry entry = servers.get(project);
        if (entry != null)
            entry.lastLoggedProblem = null;
    }

    synchronized void stopAll()
    {
        for (ServerEntry entry : servers.values())
            entry.process.close();

        servers.clear();
        lastStartProblems.clear();
    }

    private BslLanguageServerProcess startServer(IProject project, ServerKey key)
    {
        servers.remove(project);

        Path stderrLog = stateFile("logs", project.getName() + ".log"); //$NON-NLS-1$ //$NON-NLS-2$
        BslLanguageServerProcess process = new BslLanguageServerProcess(key.launchCommand(),
            project.getLocation().toFile().toPath(), stderrLog,
            message -> BslValidatorPlugin.log(BslValidatorPlugin.createErrorStatus(
                BslValidator.BSL_LS_PREFIX + project.getName() + ": " + message))); //$NON-NLS-1$
        try
        {
            LanguageServerConfigurationFile.write(key.userConfiguration(), key.effectiveConfiguration());
            process.start();
        }
        catch (IOException e)
        {
            process.close();
            logStartProblemOnce(project,
                "Не удалось запустить BSL Language Server " + key.launchCommand() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            servers.put(project, new ServerEntry(key, process));
            return null;
        }

        servers.put(project, new ServerEntry(key, process));
        lastStartProblems.remove(project);

        String configurationDescription = key.userConfiguration() == null ? "без файла конфигурации" //$NON-NLS-1$
            : "файл конфигурации " + key.userConfiguration(); //$NON-NLS-1$
        BslValidatorPlugin.log(BslValidatorPlugin.createInfoStatus(BslValidator.BSL_LS_PREFIX + "Запуск для проекта " //$NON-NLS-1$
            + project.getName() + ", " + configurationDescription + ", журнал сервера " + stderrLog)); //$NON-NLS-1$ //$NON-NLS-2$

        return process;
    }

    private ServerKey createKey(IProject project) throws IOException
    {
        IPreferenceStore store = BslValidatorPlugin.getDefault().getPreferenceStore();
        String executable = store.getString(PreferenceConstants.EXECUTABLE_PATH).trim();
        if (executable.isEmpty())
        {
            logStartProblemOnce(project, "Не задан путь к BSL Language Server: Окно > Параметры > BSL Language Server"); //$NON-NLS-1$
            return null;
        }

        if (!Files.isRegularFile(Path.of(executable)))
        {
            logStartProblemOnce(project, "Не найден файл BSL Language Server: " + executable); //$NON-NLS-1$
            return null;
        }

        Path userConfiguration = LanguageServerConfigurationFile.findUserFile(project);
        long userConfigurationModified =
            userConfiguration == null ? 0 : Files.getLastModifiedTime(userConfiguration).toMillis();

        Path effectiveConfiguration =
            stateFile("configurations", project.getName() + LanguageServerConfigurationFile.BSL_LS_FILENAME); //$NON-NLS-1$

        List<String> command = new ArrayList<>();
        if (executable.toLowerCase(Locale.ROOT).endsWith(".jar")) //$NON-NLS-1$
        {
            String java = store.getString(PreferenceConstants.JAVA_PATH).trim();
            command.add(java.isEmpty() ? "java" : java); //$NON-NLS-1$
            for (String argument : store.getString(PreferenceConstants.JVM_ARGUMENTS).trim().split("\\s+")) //$NON-NLS-1$
            {
                if (!argument.isEmpty())
                    command.add(argument);
            }
            command.add("-jar"); //$NON-NLS-1$
        }
        command.add(executable);
        command.add("lsp"); //$NON-NLS-1$
        command.add("--configuration=" + effectiveConfiguration); //$NON-NLS-1$

        return new ServerKey(command, userConfiguration, userConfigurationModified, effectiveConfiguration);
    }

    private void logStartProblemOnce(IProject project, String problem)
    {
        if (problem.equals(lastStartProblems.get(project)))
            return;

        lastStartProblems.put(project, problem);
        BslValidatorPlugin.log(BslValidatorPlugin.createErrorStatus(BslValidator.BSL_LS_PREFIX + project.getName()
            + ": " + problem)); //$NON-NLS-1$
    }

    private static Path stateFile(String directory, String fileName)
    {
        return BslValidatorPlugin.getDefault().getStateLocation().toFile().toPath().resolve(directory)
            .resolve(fileName);
    }
}
