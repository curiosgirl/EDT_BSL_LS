# BSL проверки для 1C:EDT

Плагин показывает в EDT диагностики [BSL Language Server](https://github.com/1c-syntax/bsl-language-server) (BSL LS) и предлагает их быстрые исправления.

Это переработка плагина [DoublesunRUS/ru.capralow.dt.bslls.validator](https://github.com/DoublesunRUS/ru.capralow.dt.bslls.validator) (версия 0.15, BSL LS 0.15.2 внутри) под актуальные версии BSL LS.

## Что изменилось по сравнению с 0.15

- BSL LS больше не встраивается в плагин, а запускается отдельным процессом, плагин общается с ним по протоколу LSP. Современный BSL LS требует Java 21 и устроен как Spring Boot приложение, внутрь EDT (Java 17) его не встроить. Зато сервер можно обновлять без пересборки плагина.
- Поддерживаются асинхронные методы `Асинх` / `Ждать` и остальной синтаксис, который понимает подключенная версия BSL LS.
- Один процесс BSL LS на проект EDT, запускается при первой проверке модуля. Пока сервер индексирует проект, модули пропускаются.
- Быстрые исправления из нескольких правок применяются целиком.
- К каждому замечанию добавлены быстрые исправления «Отключить <код> в строке» и «Отключить <код> во всем модуле», они вставляют комментарий `// BSLLS:<код>-off`. Команда EDT «Подавить ошибку...» на замечания внешнего валидатора не действует.
- Уровень замечания берется из BSL LS: ошибка - ошибка, предупреждение - предупреждение, информация и подсказка - информация. В тексте замечания в скобках указан код диагностики, по нему диагностику удобно отключить в `.bsl-language-server.json`.

## Требования

- 1C:EDT 2024.2 и новее.
- BSL Language Server с поддержкой `textDocument/diagnostic` (проверено на 1.0.7).

## Установка

1. Скачать `bsl-language-server_win.zip` со [страницы релизов BSL LS](https://github.com/1c-syntax/bsl-language-server/releases) и распаковать. В архиве есть своя Java, отдельно ставить не нужно.
2. В EDT: «Справка > Установить новое ПО > Добавить > Архив», выбрать `repository/target/repository-1.0.0-SNAPSHOT.zip`. Идентификаторы плагина те же, что у 0.15, поэтому установленная старая версия обновляется. Если EDT откажется обновлять, сначала удалить 0.15 через «Справка > О программе > Сведения об установке».
3. «Окно > Параметры > BSL Language Server»: указать путь к `bsl-language-server.exe`.

Вместо exe можно указать `bsl-language-server-<версия>-exec.jar`. Тогда нужна Java 21 или новее (поле «Java для jar») и при необходимости параметры JVM, по умолчанию `-Xmx4g`. Для exe память задается строкой `java-options=-Xmx4g` в `app\bsl-language-server.cfg` рядом с ним.

## Настройка диагностик

Используется обычный файл конфигурации BSL LS `.bsl-language-server.json`. Плагин ищет его по порядку:

- `<каталог проекта>\.bsl-language-server.json`
- `<каталог над проектом>\.bsl-language-server.json`
- `<рабочая область EDT>\.bsl-language-server.json`

После изменения файла сервер перезапускается при следующей проверке модуля.

Диагностики, которые уже есть в EDT, по умолчанию выключены. Включить любую из них можно явно в файле конфигурации:

- свои механизмы EDT: LineLength, ParseError, Typo, UsingServiceTag;
- аналоги проверок EDT: CodeBlockBeforeSub, CommonModuleAssign, DeprecatedMethodCall, FunctionShouldHaveReturn, ProcedureReturnsValue, ThisObjectAssign, UnknownPreprocessorSymbol, UnreachableCode, UnusedLocalMethod.

Итоговый файл, с которым запущен сервер, и журнал сервера лежат в `<рабочая область>\.metadata\.plugins\ru.capralow.dt.bslls.validator.plugin.ui\` (каталоги `configurations` и `logs`). Сообщения плагина пишутся в журнал ошибок EDT с префиксом `[BSL LS]`.

## Сборка

Нужны JDK 17+ и Maven 3.9+. Целевая платформа берется из установленного EDT, доступ к репозиториям 1С не нужен.

```bat
set EDT_HOME=C:\Program Files\1C\1CE\components\1c-edt-2025.1.5+34-x86_64
build.cmd
```

Результат: `repository\target\repository-1.0.0-SNAPSHOT.zip` (p2-репозиторий для установки в EDT).

Для разработки в Eclipse PDE: один раз выполнить сборку (она скачивает `lsp4j` и `gson` в `ru.capralow.dt.bslls.validator.plugin.ui\lib`), затем указать целевой платформой `target\default.target` с переменной окружения `EDT_HOME`.

## Лицензии

Плагин распространяется по лицензии BSD 3-Clause (см. `LICENSE`). BSL Language Server распространяется отдельно по своей [лицензии](https://github.com/1c-syntax/bsl-language-server/blob/develop/COPYING.md).
