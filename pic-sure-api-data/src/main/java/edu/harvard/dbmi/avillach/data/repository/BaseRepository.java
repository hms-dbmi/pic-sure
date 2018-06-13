package edu.harvard.dbmi.avillach.data.repository;

import org.hibernate.Session;

import javax.persistence.EntityManager;
import javax.persistence.ParameterMode;
import javax.persistence.PersistenceContext;
import javax.persistence.StoredProcedureQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 *
 * @param <T> the type of the entity class
 * @param <K> the type of the primary key
 */
public class BaseRepository<T, K> {

	protected final Class<T> type;
	
	protected BaseRepository(Class<T> type){
		this.type = type;
	}

	@PersistenceContext
	protected EntityManager em;
	
	protected EntityManager em(){
		return em;
	}
	
	protected CriteriaBuilder cb() {
		return em().getCriteriaBuilder();
	}

	/**
	 *
	 * @return a criteriaQuery instance created by criteriaBuilder in entitiyManager
	 */
	public CriteriaQuery<T> query(){
		return cb().createQuery(type);
	}

	public Predicate eq(Root root, String columnName, Object value){
		return cb().equal(root.get(columnName), value);
	}

	public T getById(K id){
		return em().find(type, id);
	}

	public List<T> list(){
		CriteriaQuery<T> query = query();
		return em().createQuery(query
				.select(query
						.from(type)))
				.getResultList();
	}

	public List<T> listByIDs(UUID... ids){
		if (ids == null || ids.length < 1)
			return list();
		return em().unwrap(Session.class).byMultipleIds(type).multiLoad(ids);
	}

	protected Root<T> root(){
		return query().from(type);
	}
	
	public class InParam<S>{
		private String parameterName;
		private Class<S> parameterValueClass;
		private S parameterValue;
		
		public InParam(Class<S> type) {
			this.parameterValueClass = type;
		}
		public String getParameterName() {
			return parameterName;
		}
		public InParam<S> name(String parameterName) {
			this.parameterName = parameterName;
			return this;
		}
		public Class<S> getParameterValueClass() {
			return parameterValueClass;
		}
		public InParam<S> type(Class<S> parameterValueClass) {
			this.parameterValueClass = parameterValueClass;
			return this;
		}
		public S getParameterValue() {
			return parameterValue;
		}
		public InParam<S> value(S parameterValue) {
			this.parameterValue = parameterValue;
			return this;
		}
	}
	
	public InParam inParam(Class type){
		return new InParam(type);
	}
	
	public StoredProcedureQuery createQueryFor(String procedureName, Class entityType, InParam ... inParams){
		StoredProcedureQuery validationsQuery = 
				em().createStoredProcedureQuery(procedureName, entityType);
		for(InParam param : inParams){
			validationsQuery.registerStoredProcedureParameter(param.parameterName, param.parameterValueClass, ParameterMode.IN)
			.setParameter(param.parameterName, param.parameterValue);			
		}
		return validationsQuery;
	}

	public void persist(T t){
		em().persist(t);
	}

	public void remove(T t){
		em().remove(t);

	}


}
