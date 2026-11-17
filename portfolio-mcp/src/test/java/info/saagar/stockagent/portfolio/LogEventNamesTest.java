package info.saagar.stockagent.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// Scans production sources so every log call starts with a literal, unique `eventName`.
///
/// This app's log lines include those written by `common`, so both source trees are scanned. Gradle
/// runs tests from the module directory.
class LogEventNamesTest {

  private static final List<Path> MAIN_SOURCE_ROOTS =
      List.of(Path.of("src/main/java"), Path.of("../common/src/main/java"));
  private static final Pattern FLUENT_LOG_CALL =
      Pattern.compile("\\blog\\.at(Trace|Debug|Info|Warn|Error)\\(\\)");
  private static final Pattern FLUENT_LOG_CALL_WITH_EVENT_NAME =
      Pattern.compile(
          "\\blog\\.at(?:Trace|Debug|Info|Warn|Error)\\(\\)\\s*"
              + "\\.addKeyValue\\(\\s*\"eventName\",\\s*\"([a-z][A-Za-z0-9]*)\"\\s*\\)");
  private static final Pattern PLAIN_LOG_CALL =
      Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\(");

  private record SourceFile(Path path, String content) {}

  private static List<SourceFile> mainSourceFiles() throws IOException {
    List<SourceFile> sourceFiles = new ArrayList<>();
    for (Path sourceRoot : MAIN_SOURCE_ROOTS) {
      try (Stream<Path> sourcePaths = Files.walk(sourceRoot)) {
        for (Path sourcePath :
            sourcePaths.filter(path -> path.toString().endsWith(".java")).toList()) {
          sourceFiles.add(new SourceFile(sourcePath, Files.readString(sourcePath)));
        }
      }
    }
    return sourceFiles;
  }

  private static long countMatches(Pattern pattern, String content) {
    return pattern.matcher(content).results().count();
  }

  @Test
  void everyLogCallStartsWithALiteralEventName() throws IOException {
    for (SourceFile sourceFile : mainSourceFiles()) {
      assertThat(countMatches(FLUENT_LOG_CALL_WITH_EVENT_NAME, sourceFile.content()))
          .as(
              "log.atX() calls in %s followed by .addKeyValue(\"eventName\", ...)",
              sourceFile.path())
          .isEqualTo(countMatches(FLUENT_LOG_CALL, sourceFile.content()));
      assertThat(countMatches(PLAIN_LOG_CALL, sourceFile.content()))
          .as("unstructured log.info(...)-style calls in %s", sourceFile.path())
          .isZero();
    }
  }

  @Test
  void eventNamesAreUniqueAcrossTheApplication() throws IOException {
    List<String> eventNames = new ArrayList<>();
    for (SourceFile sourceFile : mainSourceFiles()) {
      Matcher eventNameMatcher = FLUENT_LOG_CALL_WITH_EVENT_NAME.matcher(sourceFile.content());
      while (eventNameMatcher.find()) {
        eventNames.add(eventNameMatcher.group(1));
      }
    }

    assertThat(eventNames).isNotEmpty().doesNotHaveDuplicates();
  }
}
