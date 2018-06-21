package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;
import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class PicsureBaseEntityService <T extends BaseEntity> {

    private Logger logger;

    protected final Class<T> type;

    protected PicsureBaseEntityService (Class<T> type){
        this.type = type;
        logger = LoggerFactory.getLogger(type);
    }

    public Response getEntityById(String id, BaseRepository baseRepository){
        logger.info("Looking for " + type.getSimpleName().toLowerCase() +
                " by ID: " + id + "...");

        T t = (T) baseRepository.getById(UUID.fromString(id));

        if (t == null)
            return PICSUREResponse.protocolError(type.getSimpleName() + " is not found by given " +
                    type.getSimpleName().toLowerCase() + " ID: " + id);
        else
            return PICSUREResponse.success(t);
    }

    public Response getEntityAll(BaseRepository baseRepository){
        logger.info("Getting all " + type.getSimpleName() +
                "s...");
        List<T> ts = null;

        ts = baseRepository.list();

        if (ts == null)
            return PICSUREResponse.applicationError("Error occurs when listing all "
                    + type.getSimpleName() +
                    "s.");

        return PICSUREResponse.success(ts);
    }

    public Response addEntity(List<T> entities, BaseRepository baseRepository){
        if (entities == null || entities.isEmpty())
            return PICSUREResponse.protocolError("No " + type.getSimpleName().toLowerCase() +
                    " to be added.");

        List<T> addedEntities = addOrUpdate(entities, true, baseRepository);

        if (addedEntities.size() < entities.size())
            return PICSUREResponse.applicationError(Integer.toString(entities.size()-addedEntities.size())
                    + " " + type.getSimpleName().toLowerCase() +
                    "s are NOT operated." +
                    " Added " + type.getSimpleName().toLowerCase() +
                    "s are as follow: ", addedEntities);

        return PICSUREResponse.success("All " + type.getSimpleName().toLowerCase() +
                "s are added.", addedEntities);
    }

    public Response updateEntity(List<T> entities, BaseRepository baseRepository){
        if (entities == null || entities.isEmpty())
            return PICSUREResponse.protocolError("No " + type.getSimpleName().toLowerCase() +
                    " to be updated.");

        List<T> addedEntities = addOrUpdate(entities, false, baseRepository);

        if (addedEntities.isEmpty())
            return PICSUREResponse.error("No " + type.getSimpleName().toLowerCase() +
                    "(s) has been updated.");


        if (addedEntities.size() < entities.size())
            return PICSUREResponse.error(Integer.toString(entities.size()-addedEntities.size())
                    + " " +type.getSimpleName().toLowerCase()+
                    "s are NOT operated." +
                    " Updated " + type.getSimpleName().toLowerCase() +
                    "(s) are as follow: ", addedEntities);

        return PICSUREResponse.success("All " + type.getSimpleName().toLowerCase() +
                "(s) are updated.", addedEntities);

    }

    protected List<T> addOrUpdate(@NotNull List<T> entities, boolean forAdd, BaseRepository baseRepository){
        List<T> operatedEntities = new ArrayList<>();
        for (T t : entities){
            boolean dbContacted = false;
            if (forAdd) {
                baseRepository.persist(t);
                dbContacted = true;
            }
            else if (baseRepository.getById(t.getUuid()) != null) {
                baseRepository.merge(t);
                dbContacted = true;
            }

            if (!dbContacted || baseRepository.getById(t.getUuid()) == null){
                continue;
            }
            operatedEntities.add(t);
        }
        return operatedEntities;
    }

    public Response removeEntityById(String id, BaseRepository baseRepository) {
        UUID uuid = UUID.fromString(id);
        T t = (T) baseRepository.getById(uuid);
        if (t == null)
            return PICSUREResponse.protocolError(type.getSimpleName() +
                    " is not found by " + type.getSimpleName().toLowerCase() +
                    " ID");

        baseRepository.remove(t);

        t = (T) baseRepository.getById(uuid);
        if (t != null){
            return PICSUREResponse.applicationError("Cannot delete the " + type.getSimpleName().toLowerCase()+
                    " by id: " + id);
        }

        return PICSUREResponse.success("Successfully deleted " + type.getSimpleName().toLowerCase() +
                        " by id: " + id + ", listing rest of the " + type.getSimpleName().toLowerCase() +
                        "(s) as below"
                , baseRepository.list());

    }
}
