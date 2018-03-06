package edu.harvard.dbmi.avillach.data.repository;

import java.io.Serializable;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.ParameterMode;
import javax.persistence.PersistenceContext;
import javax.persistence.StoredProcedureQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

public class BaseRepository<T> {

	@SuppressWarnings("rawtypes")
	protected Class type;
	
	protected BaseRepository(T instance){
		type = instance.getClass();
	}

	@PersistenceContext
	protected EntityManager em;
	
	protected EntityManager em(){
		return em;
	}
	
	protected CriteriaBuilder cb() {
		return em().getCriteriaBuilder();
	}
	
	@SuppressWarnings("unchecked")
	protected CriteriaQuery<T> query(){
		return cb().createQuery(type);
	}
	
	@SuppressWarnings("rawtypes")
	protected <V> Predicate eq(Root root, String columnName, V value){
		return cb().equal(root.get(columnName), value);
	}

	@SuppressWarnings("unchecked")
	public T getById(Serializable id){
		return (T) em().find(type, id);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public List<T> list(){
		CriteriaQuery<T> query = query();
		Root root = query.from(type);
		query.select(root);
		return em.createQuery(query).getResultList();
	}
	
	@SuppressWarnings("unchecked")
	protected Root<T> root(){
		return query().from(type);
	}
	
	protected class InParam<S>{
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
	
	protected InParam inParam(Class type){
		return new InParam(type);
	}
	
	protected StoredProcedureQuery createQueryFor(String procedureName, Class entityType, InParam ... inParams){
		StoredProcedureQuery validationsQuery = 
				em.createStoredProcedureQuery(procedureName, entityType);
		for(InParam param : inParams){
			validationsQuery.registerStoredProcedureParameter(param.parameterName, param.parameterValueClass, ParameterMode.IN)
			.setParameter(param.parameterName, param.parameterValue);			
		}
		return validationsQuery;
	}
}
