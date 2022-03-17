package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
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
		List<String> requiredFieldsForTimeline = query.requiredFields;
		List<String> fieldsForTimeline = new ArrayList(query.requiredFields);
		fieldsForTimeline.addAll(query.fields);

		// wipe out required fields to not limit the patients by it
		query.requiredFields = new ArrayList<String>();

		// list patients involved
		Set<Integer> patientIds = getPatientSubsetForQuery(query);

		// get start time for the timeline
		long startTime = Long.MAX_VALUE;
		for(String field : requiredFieldsForTimeline) {
			PhenoCube cube = getCube(field);
			List<KeyAndValue> values = cube.getValuesForKeys(patientIds);
			for(KeyAndValue value : values) {
				if(value.getTimestamp()!=null && value.getTimestamp() > 0 && value.getTimestamp() < startTime) {
					startTime = value.getTimestamp();
				}
			}
		}
		final long _startTime = startTime;
		LinkedHashMap<String/* concept path */, List<TimelineEvent> /* events */> timelineEvents = 
				new LinkedHashMap<>();
		// fetch results for selected fields
		for(String concept : fieldsForTimeline) {
			PhenoCube cube = getCube(concept);
			List<KeyAndValue> values = cube.getValuesForKeys(patientIds);
			timelineEvents.put(concept, 
					values.parallelStream()
					.map( value->{
						return new TimelineEvent(value, _startTime);
					})
					.filter(event -> {
						return event.getTimestamp() > -1;
					})
					.sorted(TimelineEvent.timestampComparator)
					.collect(Collectors.toList()));
		}

		List<Entry<String, List<TimelineEvent>>> entries = new ArrayList<>(timelineEvents.entrySet());

		Collections.sort(entries, (a, b)->{
			if(a.getValue().isEmpty()) return 1;
			if(b.getValue().isEmpty()) return -1;
			return TimelineEvent.timestampComparator.compare(a.getValue().get(0), b.getValue().get(0));
		});

		timelineEvents.clear();
		entries.stream().forEach(
				(entry)->{
					timelineEvents.put(entry.getKey(), entry.getValue());
				});

		return timelineEvents;
	}
}
