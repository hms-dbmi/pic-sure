package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.local.DumpRepository;
import edu.harvard.dbmi.avillach.dump.remote.api.RemoteDictionaryAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Profile({"aggregate"})
public class RemoteDumpSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(RemoteDumpSchedulingService.class);
    private final List<RemoteDictionary> dictionaries;
    private final RemoteDictionaryRepository repository;
    private final DumpRepository localRepository;
    private final RemoteDictionaryAPI api;
    private final DataRefreshService refreshService;

    @Autowired
    public RemoteDumpSchedulingService(
        List<RemoteDictionary> dictionaries, RemoteDictionaryRepository repository, DumpRepository localRepository, RemoteDictionaryAPI api,
        DataRefreshService refreshService
    ) {
        this.dictionaries = dictionaries;
        this.repository = repository;
        this.localRepository = localRepository;
        this.api = api;
        this.refreshService = refreshService;
    }

    @Scheduled(fixedRateString = "PT1H", initialDelayString = "PT10S")
    public void pollForUpdates() {
        log.info("Polling {} remote dictionaries for updates...", dictionaries.size());
        dictionaries.stream().filter(this::shouldUpdate).forEach(refreshService::refreshDictionary);

    }

    private boolean shouldUpdate(RemoteDictionary dictionary) {
        log.info("Polling {} for last update time", dictionary.fullName());
        Optional<LocalDateTime> maybeRemoteUpdate = api.fetchUpdateTimestamp(dictionary.name());
        Optional<Integer> maybeDatabaseVersion = api.fetchDatabaseVersion(dictionary.name());
        if (maybeRemoteUpdate.isEmpty() || maybeDatabaseVersion.isEmpty()) {
            log.warn("Error reaching server {}. Will not update.", dictionary.fullName());
            return false;
        }

        Integer remoteVersion = maybeDatabaseVersion.get();
        Integer localVersion = localRepository.getDatabaseVersion();
        if (!localVersion.equals(remoteVersion)) {
            log.warn("Database version mismatch. Remote: {} != Local: {}", remoteVersion, localVersion);
            return false;
        }

        LocalDateTime remoteUpdate = maybeRemoteUpdate.get();
        log.info("The remote dictionary for {} was last updated at {}", dictionary.fullName(), remoteUpdate);
        LocalDateTime localUpdate = repository.getUpdateTimestamp(dictionary.name());
        log.info("The last local update for {} was {}", dictionary.fullName(), localUpdate);
        return localUpdate.isBefore(remoteUpdate);
    }

}
