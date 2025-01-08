package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DashboardDrawerService {

    private final DashboardDrawerRepository repository;
    private final String dashboardLayout;

    @Autowired
    public DashboardDrawerService(DashboardDrawerRepository repository, @Value("${dashboard.layout.type:default}") String dashboardLayout) {
        this.repository = repository;
        this.dashboardLayout = dashboardLayout;
    }

    /**
     * Retrieves the Dashboard Drawer for all datasets.
     *
     * @return All Dashboard Instances and their metadata.
     */
    public Optional<List<DashboardDrawer>> findAll() {
        if (dashboardLayout.equalsIgnoreCase("default")) {
            return repository.getDashboardDrawerRows();
        }
        return Optional.of(new ArrayList<>());
    }

    /**
     * Retrieves the Dashboard Drawer for a specific dataset.
     *
     *
     * @param datasetId the ID of the dataset to fetch.
     * @return a single Dashboard instance with drawer-specific metadata.
     */
    public Optional<DashboardDrawer> findByDatasetId(Integer datasetId) {
        if (dashboardLayout.equalsIgnoreCase("default")) {
            return repository.getDashboardDrawerRowsByDatasetId(datasetId);
        }
        return Optional.empty();
    }
}
