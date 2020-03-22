package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.TimelineEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class TimelineProcessor extends AbstractProcessor {

	public TimelineProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
		// TODO Auto-generated constructor stub
	}

	@Override
	public void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException {
		throw new RuntimeException("Not yet implemented.");
	}
	
	public HashMap<String/* concept path */, List<TimelineEvent> /* events */> runTimelineQuery(Query query){

		// save the requiredFields and selected fields for later use
		List<String> fieldsForTimeline = query.requiredFields;
		fieldsForTimeline.addAll(query.fields);

		// wipe out required fields to not limit the patients by it
		query.requiredFields = new ArrayList<String>();

		// list patients involved
		Set<Integer> patientIds = getPatientSubsetForQuery(query);

		// get start time for the timeline
		long startTime = Long.MAX_VALUE;
		for(String field : query.requiredFields) {
			PhenoCube cube = getCube(field);
			List<KeyAndValue> values = cube.getValuesForKeys(patientIds);
			for(KeyAndValue value : values) {
				if(value.getTimestamp() < startTime) {
					startTime = value.getTimestamp();
				}
			}
		}
		final long _startTime = startTime;
		HashMap<String/* concept path */, List<TimelineEvent> /* events */> timelineEvents = new HashMap<>();;
		// fetch results for selected fields
		for(String event : fieldsForTimeline) {
			PhenoCube cube = getCube(event);
			List<KeyAndValue> values = cube.getValuesForKeys(patientIds);
			timelineEvents.put(event, values.parallelStream().map( value->{
				return new TimelineEvent(value, _startTime);
			}).collect(Collectors.toList()));
		}
		return timelineEvents;
	}

}
