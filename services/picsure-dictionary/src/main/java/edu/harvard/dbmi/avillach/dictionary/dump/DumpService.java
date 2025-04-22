package edu.harvard.dbmi.avillach.dictionary.dump;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DumpService {
    private final DumpRepository repository;

    public DumpService(DumpRepository repository) {
        this.repository = repository;
    }

    public List<List<String>> getRowsForTable(Pageable page, DumpTable table) {
        return repository.getRowsForTable(page, table);
    }
}
