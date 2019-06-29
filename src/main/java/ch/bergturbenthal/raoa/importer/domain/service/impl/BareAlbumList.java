package ch.bergturbenthal.raoa.importer.domain.service.impl;

import ch.bergturbenthal.raoa.importer.domain.service.AlbumList;
import ch.bergturbenthal.raoa.importer.domain.service.FileImporter;
import ch.bergturbenthal.raoa.importer.domain.service.GitAccess;
import ch.bergturbenthal.raoa.importer.domain.service.Updater;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

@Slf4j
public class BareAlbumList implements AlbumList {
  private static final Collection<String> IMPORTING_TYPES =
      new HashSet<>(Arrays.asList("image/jpeg", "image/tiff", "application/mp4"));
  private final SortedMap<Instant, Path> autoaddIndex;
  private final Map<Path, GitAccess> repositories;
  private Path directory;

  public BareAlbumList(Path directory) {
    this.directory = directory;

    repositories =
        listSubdirs(directory)
            .collect(
                Collectors.toMap(
                    p -> p,
                    p -> {
                      try {
                        return BareGitAccess.accessOf(p);
                      } catch (IOException e) {
                        throw new RuntimeException("Cannot open repository of " + p, e);
                      }
                    }));
    autoaddIndex =
        repositories.entrySet().stream()
            .flatMap(e -> e.getValue().readAutoadd().map(t -> new AutoaddEntry(t, e.getKey())))
            .collect(
                Collectors.toMap(
                    AutoaddEntry::getTime, AutoaddEntry::getPath, (a, b) -> b, TreeMap::new));
  }

  private static Stream<Path> listSubdirs(Path dir) {
    try {
      return Files.list(dir)
          .filter(e -> Files.isDirectory(e))
          .flatMap(
              d -> {
                if (d.getFileName().toString().endsWith(".git")) {
                  return Stream.of(d);
                } else {
                  return listSubdirs(d);
                }
              });
    } catch (IOException e) {
      log.error("Cannot access directory " + dir, e);
    }
    return Stream.empty();
  }

  @Override
  public FileImporter createImporter() {
    return new FileImporter() {
      private final Map<Path, Updater> pendingUpdaters = new HashMap<>();

      @Override
      public synchronized boolean importFile(final Path file) throws IOException {
        try {
          AutoDetectParser parser = new AutoDetectParser();
          BodyContentHandler handler = new BodyContentHandler();
          Metadata metadata = new Metadata();
          final TikaInputStream inputStream = TikaInputStream.get(file);
          parser.parse(inputStream, handler, metadata);
          if (!IMPORTING_TYPES.contains(metadata.get(Metadata.CONTENT_TYPE))) {
            log.info("Unsupported content type: " + metadata.get(Metadata.CONTENT_TYPE));
            return false;
          }
          final Date createDate = metadata.getDate(TikaCoreProperties.CREATED);
          if (createDate == null) {
            log.info("No creation timestamp");
            return false;
          }
          final Instant createTimestamp = createDate.toInstant();

          final String prefix =
              DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                  .format(createTimestamp.atZone(ZoneId.systemDefault()));
          final String targetFilename = prefix + "-" + file.getFileName().toString();
          return albumOf(createTimestamp)
              .map(
                  repositoryPath -> {
                    log.info("Import " + file + " to " + repositoryPath);
                    return pendingUpdaters.computeIfAbsent(
                        repositoryPath, k -> repositories.get(k).createUpdater());
                  })
              .map(
                  foundRepository -> {
                    try {
                      return foundRepository.importFile(file, targetFilename);
                    } catch (IOException e) {
                      log.warn("Cannot import file " + file, e);
                      return false;
                    }
                  })
              .orElse(false);

        } catch (TikaException | SAXException e) {
          log.warn("Cannot access file " + file, e);
          return false;
        }
      }

      @Override
      public synchronized boolean commitAll() {
        try {
          return pendingUpdaters.values().stream()
              .map(Updater::commit)
              .reduce((b1, b2) -> b1 && b2)
              .orElse(true);
        } finally {
          pendingUpdaters.clear();
        }
      }
    };
  }

  private Optional<Path> albumOf(final Instant timestamp) {
    final SortedMap<Instant, Path> headMap = autoaddIndex.headMap(timestamp);
    if (headMap.isEmpty()) return Optional.empty();
    return Optional.of(autoaddIndex.get(headMap.lastKey()));
  }

  @Value
  private static class AutoaddEntry {
    private Instant time;
    private Path path;
  }
}