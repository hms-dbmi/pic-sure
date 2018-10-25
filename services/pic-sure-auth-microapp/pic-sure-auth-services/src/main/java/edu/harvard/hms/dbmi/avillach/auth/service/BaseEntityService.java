package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;
import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.validation.constraints.NotNull;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class BaseEntityService<T extends BaseEntity> {

    private Logger logger;

    protected final Class<T> type;

    @Context
    SecurityContext securityContext;

    protected BaseEntityService(Class<T> type){
        this.type = type;
        logger = LoggerFactory.getLogger(type);
    }

    public Response getEntityById(String id, BaseRepository baseRepository){
        logger.info("User: " + JAXRSConfiguration.getPrincipalName(securityContext)
                + " Looking for " + type.getSimpleName() +
                " by ID: " + id + "...");

        T t = (T) baseRepository.getById(UUID.fromString(id));

        if (t == null)
            return PICSUREResponse.protocolError(type.getSimpleName() + " is not found by given " +
                    type.getSimpleName().toLowerCase() + " ID: " + id);
        else
            return PICSUREResponse.success(t);
    }

    public Response getEntityAll(BaseRepository baseRepository){
        logger.info("User: " + JAXRSConfiguration.getPrincipalName(securityContext) +
                " Getting all " + type.getSimpleName() +
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

        logger.info("User: " + JAXRSConfiguration.getPrincipalName(securityContext) + " is trying to add a list of "
                + type.getSimpleName());

        List<T> addedEntities = addOrUpdate(entities, true, baseRepository);

        if (addedEntities.isEmpty())
            return PICSUREResponse.protocolError("No " + type.getSimpleName().toLowerCase() +
                    "(s) has been added.");

        if (addedEntities.size() < entities.size())
            return PICSUREResponse.success(Integer.toString(entities.size()-addedEntities.size())
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

        logger.info("User: " + JAXRSConfiguration.getPrincipalName(securityContext) + " is trying to update a list of "
                + type.getSimpleName());

        List<T> addedEntities = addOrUpdate(entities, false, baseRepository);

        if (addedEntities.isEmpty())
            return PICSUREResponse.protocolError("No " + type.getSimpleName().toLowerCase() +
                    "(s) has been updated.");


        if (addedEntities.size() < entities.size())
            return PICSUREResponse.success(Integer.toString(entities.size()-addedEntities.size())
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
                t.setUuid(null);
                baseRepository.persist(t);
                dbContacted = true;
            }
            else {
                if (updateAllAttributes(t, baseRepository)){
                    dbContacted = true;
                }
            }

            if (!dbContacted || t.getUuid() == null || baseRepository.getById(t.getUuid()) == null){
                continue;
            }

            t = (T) baseRepository.getById(t.getUuid());
            operatedEntities.add(t);
        }
        return operatedEntities;
    }

    private boolean updateAllAttributes(T detachedT, BaseRepository<T, UUID> baseRepository){
        UUID uuid = detachedT.getUuid();
        if (uuid == null)
            return false;

        T retrievedT = baseRepository.getById(uuid);

        if (retrievedT == null)
            return false;

        try {
            for (Field field : detachedT.getClass().getDeclaredFields()){
                String fieldName = field.getName();
                fieldName = fieldName.substring(0,1).toUpperCase() + fieldName.substring(1);
                String getter = "get" + fieldName;
                Class<?> type = field.getType();
                if (type == boolean.class || type == null) {
                    getter = "is" + fieldName;
                }

                String setter = "set" + fieldName;

                Object value = detachedT.getClass().getMethod(getter).invoke(detachedT);
                if (value != null)
                    detachedT.getClass().getMethod(setter, field.getType())
                            .invoke(retrievedT, value);

                /**
                 * This PropertyDescriptor is in java.beans package, which is the kind of
                 * standard way of doing getter and setter, but there is one thing
                 * this method is checking the return type of getter setter, if setter
                 * return the Class object (which is pretty useful and normal these days),
                 * this method will throw an IntrospectionException...,
                 * but! I don't think using PropertyDescriptor to look for getter setter has
                 * the best performance though...
                 */
//                PropertyDescriptor pd = new PropertyDescriptor(fieldName, detachedT.getClass());
//                Object value = pd.getReadMethod()
//                        .invoke(detachedT);
//                if (value != null){
//                    pd.getWriteMethod().invoke(retrievedT, value);
//                }
            }
        } catch (IllegalArgumentException | ReflectiveOperationException ex /* | IntrospectionException ex */){
            ex.printStackTrace();
            return false;
        }

        baseRepository.merge(retrievedT);

        return true;
    }

    public Response removeEntityById(String id, BaseRepository baseRepository) {

        logger.info("User: " + JAXRSConfiguration.getPrincipalName(securityContext) + " is trying to REMOVE a entity: "
                + type.getSimpleName() + ", by uuid: " + id);

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
