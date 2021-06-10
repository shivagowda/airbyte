package io.airbyte.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.io.Archives;
import io.airbyte.commons.lang.CloseableConsumer;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.commons.yaml.Yamls;
import io.airbyte.scheduler.persistence.JobPersistence;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;

public class ConfigDumpExport {

  private static final String ARCHIVE_FILE_NAME = "airbyte_config_dump";
  private static final String CONFIG_FOLDER_NAME = "airbyte_config";
  private static final String DB_FOLDER_NAME = "airbyte_db";
  private static final String VERSION_FILE_NAME = "VERSION";

  private final ConfigDumpUtil configDumpUtil;
  private final JobPersistence jobPersistence;
  private final String version;

  public ConfigDumpExport(Path storageRoot, JobPersistence jobPersistence, String version) {
    this.configDumpUtil = new ConfigDumpUtil(storageRoot);
    this.jobPersistence = jobPersistence;
    this.version = version;
  }

  public File dump() {
    try {
      final Path tempFolder = Files.createTempDirectory(Path.of("/tmp"), ARCHIVE_FILE_NAME);
      final File dump = Files.createTempFile(ARCHIVE_FILE_NAME, ".tar.gz").toFile();
      exportVersionFile(tempFolder);
      dumpConfigs(tempFolder);
      dumpDatabase(tempFolder);

      Archives.createArchive(tempFolder, dump.toPath());
      return dump;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void exportVersionFile(Path tempFolder) throws IOException {
    final File versionFile = Files.createFile(tempFolder.resolve(VERSION_FILE_NAME)).toFile();
    FileUtils.writeStringToFile(versionFile, version, Charset.defaultCharset());
  }

  private void dumpDatabase(Path parentFolder) throws Exception {
    final Map<String, Stream<JsonNode>> tables = jobPersistence.exportEverythingInDefaultSchema();
    Files.createDirectories(parentFolder.resolve(DB_FOLDER_NAME));
    for (Map.Entry<String, Stream<JsonNode>> table : tables.entrySet()) {
      final Path tablePath = buildTablePath(parentFolder, table.getKey());
      writeTableToArchive(tablePath, table.getValue());
    }
  }

  private void writeTableToArchive(final Path tablePath, final Stream<JsonNode> tableStream)
      throws Exception {
    Files.createDirectories(tablePath.getParent());
    final BufferedWriter recordOutputWriter = new BufferedWriter(
        new FileWriter(tablePath.toFile()));
    final CloseableConsumer<JsonNode> recordConsumer = Yamls.listWriter(recordOutputWriter);
    tableStream.forEach(row -> Exceptions.toRuntime(() -> {
      recordConsumer.accept(row);
    }));
    recordConsumer.close();
  }

  protected static Path buildTablePath(final Path storageRoot, final String tableName) {
    return storageRoot
        .resolve(DB_FOLDER_NAME)
        .resolve(String.format("%s.yaml", tableName.toUpperCase()));
  }

  public void dumpConfigs(Path parentFolder) throws IOException {
    List<String> directories = configDumpUtil.listDirectories();
    for (String directory : directories) {
      List<JsonNode> configList = configDumpUtil.listConfig(directory);

      writeConfigsToArchive(parentFolder, directory, configList);
    }
  }

  protected void writeConfigsToArchive(final Path storageRoot, final String schemaType,
      final List<JsonNode> configList) throws IOException {
    final Path configPath = buildConfigPath(storageRoot, schemaType);
    Files.createDirectories(configPath.getParent());
    if (!configList.isEmpty()) {
      final List<JsonNode> sortedConfigs = configList.stream()
          .sorted(Comparator.comparing(JsonNode::toString)).collect(
              Collectors.toList());
      Files.writeString(configPath, Yamls.serialize(sortedConfigs));
    } else {
      // Create empty file
      Files.createFile(configPath);
    }
  }

  protected static Path buildConfigPath(final Path storageRoot, final String schemaType) {
    return storageRoot.resolve(CONFIG_FOLDER_NAME)
        .resolve(String.format("%s.yaml", schemaType));
  }
}
