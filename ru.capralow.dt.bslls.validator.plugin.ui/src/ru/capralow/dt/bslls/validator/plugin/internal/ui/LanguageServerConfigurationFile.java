package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Готовит файл конфигурации для запуска BSL LS: берет пользовательский .bsl-language-server.json
 * и выключает диагностики, которые уже есть в EDT, если пользователь не задал их явно.
 */
final class LanguageServerConfigurationFile
{
    static final String BSL_LS_FILENAME = ".bsl-language-server.json"; //$NON-NLS-1$

    // Свои механизмы и аналогичные проверки уже есть в EDT
    private static final String[] DUPLICATE_DIAGNOSTICS = { "LineLength", //$NON-NLS-1$
        "ParseError", //$NON-NLS-1$
        "Typo", //$NON-NLS-1$
        "UsingServiceTag", //$NON-NLS-1$
        "CodeBlockBeforeSub", //$NON-NLS-1$
        "CommonModuleAssign", //$NON-NLS-1$
        "DeprecatedMethodCall", //$NON-NLS-1$
        "FunctionShouldHaveReturn", //$NON-NLS-1$
        "ProcedureReturnsValue", //$NON-NLS-1$
        "ThisObjectAssign", //$NON-NLS-1$
        "UnknownPreprocessorSymbol", //$NON-NLS-1$
        "UnreachableCode", //$NON-NLS-1$
        "UnusedLocalMethod" }; //$NON-NLS-1$

    // Относительные пути в этих параметрах BSL LS считает от каталога файла конфигурации
    private static final String[] PATH_PARAMETERS = { "configurationRoot", "traceLog" }; //$NON-NLS-1$ //$NON-NLS-2$

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static List<Path> candidatePaths(IProject project)
    {
        Path projectPath = project.getLocation().toFile().toPath();

        List<Path> paths = new ArrayList<>();
        paths.add(projectPath.resolve(BSL_LS_FILENAME));
        if (projectPath.getParent() != null)
            paths.add(projectPath.getParent().resolve(BSL_LS_FILENAME));
        paths.add(Platform.getLocation().toFile().toPath().resolve(BSL_LS_FILENAME));

        return paths;
    }

    static Path findUserFile(IProject project)
    {
        for (Path path : candidatePaths(project))
        {
            if (Files.isRegularFile(path))
                return path;
        }

        return null;
    }

    static Path write(Path userFile, Path targetFile) throws IOException
    {
        JsonObject configuration = userFile == null ? new JsonObject() : read(userFile);

        if (userFile != null)
            makePathsAbsolute(configuration, userFile.toAbsolutePath().getParent());

        JsonObject parameters = getOrCreateObject(getOrCreateObject(configuration, "diagnostics"), "parameters"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String diagnosticCode : DUPLICATE_DIAGNOSTICS)
        {
            if (!parameters.has(diagnosticCode))
                parameters.addProperty(diagnosticCode, false);
        }

        Files.createDirectories(targetFile.getParent());
        try (Writer writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8))
        {
            GSON.toJson(configuration, writer);
        }

        return targetFile;
    }

    private static JsonObject read(Path userFile) throws IOException
    {
        try (Reader reader = Files.newBufferedReader(userFile, StandardCharsets.UTF_8))
        {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject())
                throw new IOException("Корень файла конфигурации должен быть объектом: " + userFile); //$NON-NLS-1$

            return element.getAsJsonObject();
        }
        catch (JsonParseException e)
        {
            throw new IOException("Не удалось разобрать файл конфигурации " + userFile, e); //$NON-NLS-1$
        }
    }

    private static void makePathsAbsolute(JsonObject configuration, Path baseDirectory)
    {
        for (String parameter : PATH_PARAMETERS)
        {
            JsonElement value = configuration.get(parameter);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                continue;

            Path path = Path.of(value.getAsString());
            if (!path.isAbsolute())
                configuration.addProperty(parameter, baseDirectory.resolve(path).normalize().toString());
        }
    }

    private static JsonObject getOrCreateObject(JsonObject parent, String name)
    {
        JsonElement element = parent.get(name);
        if (element != null && element.isJsonObject())
            return element.getAsJsonObject();

        JsonObject object = new JsonObject();
        parent.add(name, object);
        return object;
    }

    private LanguageServerConfigurationFile()
    {
    }
}
